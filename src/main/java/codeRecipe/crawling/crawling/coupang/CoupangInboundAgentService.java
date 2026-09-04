package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.domain.CoupangInboundInvoice;
import codeRecipe.crawling.crawling.domain.CoupangInboundPlan;
import codeRecipe.crawling.crawling.domain.CoupangInboundPlanItem;
import codeRecipe.crawling.crawling.domain.CoupangWingAddress;
import codeRecipe.crawling.crawling.domain.InboundPlanStatus;
import codeRecipe.crawling.crawling.repository.CoupangFulfillmentCenterRepository;
import codeRecipe.crawling.crawling.repository.CoupangInboundInvoiceRepository;
import codeRecipe.crawling.crawling.repository.CoupangInboundPlanItemRepository;
import codeRecipe.crawling.crawling.repository.CoupangInboundPlanRepository;
import codeRecipe.crawling.crawling.repository.CoupangInboundSettingRepository;
import codeRecipe.crawling.crawling.repository.CoupangInventoryRepository;
import codeRecipe.crawling.crawling.repository.CoupangWingAddressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * RPA 에이전트(담당자 PC) 연동 (의뢰 #8).
 * 작업 큐 제공 + 실행 결과 반영 + 문서(PDF) 수신 + WING 주소 동기화.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangInboundAgentService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** RPA_RUNNING이 이 시간 이상 갱신되지 않으면 에이전트 중단으로 보고 작업을 다시 내려준다 */
    private static final int STALE_RUNNING_MINUTES = 30;

    private final CoupangInboundPlanRepository planRepository;
    private final CoupangInboundPlanItemRepository itemRepository;
    private final CoupangInboundInvoiceRepository invoiceRepository;
    private final CoupangInboundSettingRepository settingRepository;
    private final CoupangWingAddressRepository wingAddressRepository;
    private final CoupangFulfillmentCenterRepository fulfillmentCenterRepository;
    private final CoupangInventoryRepository inventoryRepository;
    private final CoupangSlackNotifier slackNotifier;

    @Value("${coupang.slack.order-screen-url:}")
    private String screenBaseUrl;

    public record AgentItem(Long vendorItemId, String productName, Integer quantity) { }

    public record AgentInvoice(Integer boxNo, String trackingNumber) { }

    public record AgentJob(String type, Long planId, Integer boxCount, String returnAddress,
                           String wingInboundId, List<AgentItem> items, List<AgentInvoice> invoices) { }

    /** 에이전트 폴링/프로토콜 기동 시 실행할 작업 목록 */
    @Transactional(readOnly = true)
    public List<AgentJob> pendingJobs() {
        List<AgentJob> jobs = new ArrayList<>();

        // WING 주소 미동기화 상태면 최우선으로 동기화 작업 지시 (회송지 설정의 선행 조건)
        if (wingAddressRepository.count() == 0) {
            jobs.add(new AgentJob("SYNC_ADDRESSES", null, null, null, null, List.of(), List.of()));
        }

        String returnAddress = settingRepository.findById(CoupangInboundPlanService.SETTING_RETURN_ADDRESS)
                .map(setting -> setting.getSettingValue())
                .orElse(null);

        LocalDateTime staleBefore = LocalDateTime.now(SEOUL).minusMinutes(STALE_RUNNING_MINUTES);
        List<CoupangInboundPlan> submitCandidates = planRepository.findByStatusInOrderByRequestedAtAsc(
                Set.of(InboundPlanStatus.REQUESTED, InboundPlanStatus.RPA_RUNNING));
        for (CoupangInboundPlan plan : submitCandidates) {
            if (plan.getStatus() == InboundPlanStatus.RPA_RUNNING && plan.getUpdatedAt().isAfter(staleBefore)) {
                continue; // 실행 중 — 재지시하지 않음
            }
            jobs.add(new AgentJob("SUBMIT", plan.getPlanId(), plan.getBoxCount(), returnAddress, null,
                    toAgentItems(plan.getPlanId()), List.of()));
        }

        List<CoupangInboundPlan> invoicePlans = planRepository.findByStatusInOrderByRequestedAtAsc(
                Set.of(InboundPlanStatus.INVOICE_ISSUED));
        for (CoupangInboundPlan plan : invoicePlans) {
            List<AgentInvoice> unregistered = invoiceRepository.findByPlanIdOrderByBoxNoAsc(plan.getPlanId()).stream()
                    .filter(invoice -> invoice.getRegisteredAt() == null)
                    .map(invoice -> new AgentInvoice(invoice.getBoxNo(), invoice.getTrackingNumber()))
                    .toList();
            if (unregistered.isEmpty()) {
                continue;
            }
            jobs.add(new AgentJob("REGISTER_INVOICE", plan.getPlanId(), plan.getBoxCount(), null,
                    plan.getWingInboundId(), List.of(), unregistered));
        }
        return jobs;
    }

    /** SUBMIT 작업 시작 표시 (REQUESTED → RPA_RUNNING) */
    @Transactional
    public void start(long planId) {
        CoupangInboundPlan plan = requirePlan(planId);
        if (plan.getStatus() != InboundPlanStatus.REQUESTED && plan.getStatus() != InboundPlanStatus.RPA_RUNNING) {
            throw new IllegalStateException("실행 시작 가능 상태가 아닙니다: " + plan.getStatus());
        }
        planRepository.save(plan.toBuilder()
                .status(InboundPlanStatus.RPA_RUNNING)
                .updatedAt(LocalDateTime.now(SEOUL))
                .build());
    }

    /**
     * WING 제출 결과 반영. 성공 시 제출 시점 재고를 기준선으로 저장한다 (입고 반영 추정용).
     * 문서(바코드/부착문서)는 에이전트가 사내 CDN(refrigerator)에 올린 뒤 URL로 함께 보고한다 (회수 실패 시 null).
     */
    @Transactional
    public void submitResult(long planId, boolean success, String wingInboundId,
                             String fulfillmentCenter, String arrivalDate, String failReason,
                             String barcodePdfUrl, String attachPdfUrl) {
        CoupangInboundPlan plan = requirePlan(planId);
        LocalDateTime now = LocalDateTime.now(SEOUL);

        if (!success) {
            planRepository.save(plan.toBuilder()
                    .status(InboundPlanStatus.FAILED)
                    .failReason(failReason == null ? "사유 미상" : failReason)
                    .updatedAt(now)
                    .build());
            slackNotifier.send("🚨 [로켓그로스 입고] 자동 입력 실패 — 계획 #" + planId
                    + "\n사유: " + (failReason == null ? "사유 미상" : failReason)
                    + "\nWING에서 수동 진행해주세요. 상품/수량은 신청 상세 참고" + detailLink(planId));
            return;
        }

        List<CoupangInboundPlanItem> items = itemRepository.findByPlanId(planId);
        int totalQuantity = 0;
        for (CoupangInboundPlanItem item : items) {
            Integer baseline = inventoryRepository.findByVendorItemId(item.getVendorItemId())
                    .map(inventory -> inventory.getOrderableQuantity())
                    .orElse(null);
            itemRepository.save(item.toBuilder().baselineQuantity(baseline).build());
            totalQuantity += item.getQuantity();
        }

        planRepository.save(plan.toBuilder()
                .status(InboundPlanStatus.SUBMITTED)
                .wingInboundId(wingInboundId)
                .fulfillmentCenter(fulfillmentCenter)
                .arrivalDate(arrivalDate == null || arrivalDate.isBlank() ? null : LocalDate.parse(arrivalDate))
                .barcodePdfUrl(blankToNull(barcodePdfUrl))
                .attachPdfUrl(blankToNull(attachPdfUrl))
                .failReason(null)
                .submittedAt(now)
                .updatedAt(now)
                .build());

        StringBuilder message = new StringBuilder();
        message.append("📦 [로켓그로스 입고] WING 제출 완료 — 입고 ID ").append(wingInboundId)
                .append("\n물류센터 ").append(fulfillmentCenter)
                .append(" · 도착예정일 ").append(arrivalDate)
                .append(" · 상품 ").append(items.size()).append("건 / 총 ").append(totalQuantity)
                .append("개 / 박스 ").append(plan.getBoxCount()).append("개")
                .append("\n다음: 바코드 부착·포장 후 30-we에서 송장 발급").append(detailLink(planId));
        if (blankToNull(barcodePdfUrl) != null) {
            message.append("\n📎 상품 바코드 PDF: ").append(barcodePdfUrl);
        }
        if (blankToNull(attachPdfUrl) != null) {
            message.append("\n📎 물류 부착문서 PDF: ").append(attachPdfUrl);
        }
        if (fulfillmentCenter != null && fulfillmentCenterRepository.findByFcName(fulfillmentCenter).isEmpty()) {
            message.append("\n⚠️ FC 주소 미등록(").append(fulfillmentCenter)
                    .append(") — coupang_fulfillment_center에 등록해야 송장 발급이 가능합니다");
        }
        slackNotifier.send(message.toString());
    }

    /** WING 송장 등록 결과 반영 */
    @Transactional
    public void invoiceResult(long planId, boolean success, String failReason) {
        CoupangInboundPlan plan = requirePlan(planId);
        LocalDateTime now = LocalDateTime.now(SEOUL);

        if (!success) {
            // 상태 유지(INVOICE_ISSUED) — 재시도 대상으로 남긴다
            planRepository.save(plan.toBuilder().updatedAt(now).build());
            slackNotifier.send("⚠️ [로켓그로스 입고] WING 송장 등록 실패 — 입고 ID " + plan.getWingInboundId()
                    + "\n사유: " + (failReason == null ? "사유 미상" : failReason)
                    + "\n에이전트가 재시도합니다. 반복 실패 시 WING에서 직접 등록해주세요" + detailLink(planId));
            return;
        }

        List<CoupangInboundInvoice> invoices = invoiceRepository.findByPlanIdOrderByBoxNoAsc(planId);
        for (CoupangInboundInvoice invoice : invoices) {
            if (invoice.getRegisteredAt() == null) {
                invoiceRepository.save(invoice.toBuilder().registeredAt(now).build());
            }
        }
        planRepository.save(plan.toBuilder()
                .status(InboundPlanStatus.INVOICE_REGISTERED)
                .updatedAt(now)
                .build());
        slackNotifier.send("🚚 [로켓그로스 입고] WING 송장 등록 완료 — 입고 ID " + plan.getWingInboundId()
                + " · 송장 " + invoices.size() + "건. 발송만 남았습니다" + detailLink(planId));
    }

    /** 회수 문서 CDN URL 보고 (제출 시 함께 못 보낸 경우의 후속/재보고용). type: barcode | attachment */
    @Transactional
    public void saveDocumentUrl(long planId, String type, String url) {
        if (!"barcode".equals(type) && !"attachment".equals(type)) {
            throw new IllegalArgumentException("지원하지 않는 문서 유형: " + type);
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("문서 URL이 비어 있습니다");
        }
        CoupangInboundPlan plan = requirePlan(planId);
        CoupangInboundPlan.CoupangInboundPlanBuilder builder = plan.toBuilder()
                .updatedAt(LocalDateTime.now(SEOUL));
        if ("barcode".equals(type)) {
            builder.barcodePdfUrl(url);
        } else {
            builder.attachPdfUrl(url);
        }
        planRepository.save(builder.build());
        log.info("입고 문서 URL 저장 planId={} type={} url={}", planId, type, url);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** WING 회송지 주소 목록 동기화 (upsert + 사라진 주소 제거) */
    @Transactional
    public int syncAddresses(List<String> addresses) {
        List<String> cleaned = addresses == null ? List.of()
                : addresses.stream().filter(a -> a != null && !a.isBlank()).map(String::trim).distinct().toList();
        if (cleaned.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(SEOUL);
        for (String address : cleaned) {
            CoupangWingAddress row = wingAddressRepository.findByAddressText(address)
                    .map(existing -> existing.toBuilder().syncedAt(now).build())
                    .orElse(CoupangWingAddress.builder().addressText(address).syncedAt(now).build());
            wingAddressRepository.save(row);
        }
        List<CoupangWingAddress> stale = wingAddressRepository.findByAddressTextNotIn(cleaned);
        if (!stale.isEmpty()) {
            wingAddressRepository.deleteAll(stale);
        }
        log.info("WING 주소 동기화: {}건 (제거 {}건)", cleaned.size(), stale.size());
        return cleaned.size();
    }

    /** 에이전트 상태 알림 릴레이 (세션 만료, 문서 회수 실패 등) — 웹훅을 에이전트에 두지 않기 위한 통로 */
    public void notifyFromAgent(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        slackNotifier.send("🤖 [입고 에이전트] " + message);
    }

    private List<AgentItem> toAgentItems(Long planId) {
        return itemRepository.findByPlanId(planId).stream()
                .map(item -> new AgentItem(item.getVendorItemId(), item.getProductName(), item.getQuantity()))
                .toList();
    }

    private CoupangInboundPlan requirePlan(long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("계획을 찾을 수 없습니다: " + planId));
    }

    private String detailLink(long planId) {
        if (screenBaseUrl == null || screenBaseUrl.isBlank()) {
            return "";
        }
        return "\n" + screenBaseUrl + "/admin/coupang/inbound/plans/" + planId;
    }
}
