package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.domain.CoupangInboundInvoice;
import codeRecipe.crawling.crawling.domain.CoupangInboundPlan;
import codeRecipe.crawling.crawling.domain.CoupangInboundPlanItem;
import codeRecipe.crawling.crawling.domain.CoupangInboundSetting;
import codeRecipe.crawling.crawling.domain.CoupangRestockSuggestion;
import codeRecipe.crawling.crawling.domain.InboundPlanStatus;
import codeRecipe.crawling.crawling.domain.RestockStatus;
import codeRecipe.crawling.crawling.repository.CoupangInboundInvoiceRepository;
import codeRecipe.crawling.crawling.repository.CoupangInboundPlanItemRepository;
import codeRecipe.crawling.crawling.repository.CoupangInboundPlanRepository;
import codeRecipe.crawling.crawling.repository.CoupangInboundSettingRepository;
import codeRecipe.crawling.crawling.repository.CoupangInventoryRepository;
import codeRecipe.crawling.crawling.repository.CoupangRestockSuggestionRepository;
import codeRecipe.crawling.crawling.repository.CoupangWingAddressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * 로켓그로스 입고 계획 — 30-we 연동 액션 (의뢰 #8).
 * 계획 생성 / 송장 접수 / 회송지 설정 / 문서 제공. WING 실행 자체는 에이전트가 수행한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangInboundPlanService {

    public static final String SETTING_RETURN_ADDRESS = "RETURN_ADDRESS";

    /** 아직 완결되지 않아 같은 SKU의 중복 신청을 막아야 하는 상태들 */
    public static final Set<InboundPlanStatus> ACTIVE_STATUSES = Set.of(
            InboundPlanStatus.REQUESTED, InboundPlanStatus.RPA_RUNNING, InboundPlanStatus.SUBMITTED,
            InboundPlanStatus.INVOICE_ISSUED, InboundPlanStatus.INVOICE_REGISTERED);

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CoupangInboundPlanRepository planRepository;
    private final CoupangInboundPlanItemRepository itemRepository;
    private final CoupangInboundInvoiceRepository invoiceRepository;
    private final CoupangInboundSettingRepository settingRepository;
    private final CoupangWingAddressRepository wingAddressRepository;
    private final CoupangRestockSuggestionRepository suggestionRepository;
    private final CoupangInventoryRepository inventoryRepository;

    public record NewItem(Long vendorItemId, Integer quantity) { }

    public record InvoiceEntry(Integer boxNo, String trackingNumber) { }

    public static class ReturnAddressNotSetException extends RuntimeException {
        public ReturnAddressNotSetException() { super("회송지가 설정되지 않았습니다. 30-we 입고 설정에서 회송지를 먼저 선택해주세요."); }
    }

    public static class DuplicateInProgressException extends RuntimeException {
        public DuplicateInProgressException(String message) { super(message); }
    }

    public static class AddressNotInWingException extends RuntimeException {
        public AddressNotInWingException(String message) { super(message); }
    }

    /** 입고 계획 생성 (30-we [입고 신청하기]). 해당 SKU의 재입고 제안은 REQUESTED로 전환된다. */
    @Transactional
    public Long createPlan(List<NewItem> items, int boxCount, String requestedBy) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("입고할 상품이 없습니다");
        }
        if (boxCount < 1) {
            throw new IllegalArgumentException("박스 수는 1 이상이어야 합니다");
        }
        for (NewItem item : items) {
            if (item.vendorItemId() == null || item.quantity() == null || item.quantity() < 1) {
                throw new IllegalArgumentException("상품/수량이 올바르지 않습니다: " + item);
            }
        }
        if (settingRepository.findById(SETTING_RETURN_ADDRESS).isEmpty()) {
            throw new ReturnAddressNotSetException();
        }

        List<Long> vendorItemIds = items.stream().map(NewItem::vendorItemId).toList();
        List<Long> duplicates = itemRepository.findVendorItemIdsInPlans(vendorItemIds, ACTIVE_STATUSES);
        if (!duplicates.isEmpty()) {
            throw new DuplicateInProgressException("이미 진행 중인 입고에 포함된 상품입니다: vendorItemId " + duplicates);
        }

        LocalDateTime now = LocalDateTime.now(SEOUL);
        CoupangInboundPlan plan = planRepository.save(CoupangInboundPlan.builder()
                .status(InboundPlanStatus.REQUESTED)
                .boxCount(boxCount)
                .requestedBy(requestedBy)
                .requestedAt(now)
                .updatedAt(now)
                .build());

        for (NewItem item : items) {
            String productName = inventoryRepository.findByVendorItemId(item.vendorItemId())
                    .map(inv -> inv.getProductName())
                    .orElse(null);
            itemRepository.save(CoupangInboundPlanItem.builder()
                    .planId(plan.getPlanId())
                    .vendorItemId(item.vendorItemId())
                    .productName(productName)
                    .quantity(item.quantity())
                    .build());
        }

        // 재입고 제안 상태 전환 (SUGGESTED → REQUESTED) — 의뢰 #2 화면에 반영됨
        List<CoupangRestockSuggestion> suggestions =
                suggestionRepository.findByVendorItemIdInAndStatus(vendorItemIds, RestockStatus.SUGGESTED);
        for (CoupangRestockSuggestion suggestion : suggestions) {
            suggestionRepository.save(suggestion.toBuilder().status(RestockStatus.REQUESTED).build());
        }

        log.info("입고 계획 생성 planId={} 상품 {}건 박스 {}개 신청자={}",
                plan.getPlanId(), items.size(), boxCount, requestedBy);
        return plan.getPlanId();
    }

    /** 로젠 송장 접수 (30-we [송장 발급] 후 호출). 상태를 INVOICE_ISSUED로 전환해 에이전트의 WING 등록을 유발한다. 재호출(재발급) 멱등. */
    @Transactional
    public InboundPlanStatus registerInvoices(long planId, List<InvoiceEntry> invoices) {
        CoupangInboundPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("계획을 찾을 수 없습니다: " + planId));
        if (plan.getStatus() != InboundPlanStatus.SUBMITTED && plan.getStatus() != InboundPlanStatus.INVOICE_ISSUED) {
            throw new IllegalStateException("송장 접수 가능 상태가 아닙니다: " + plan.getStatus());
        }
        if (invoices == null || invoices.size() != plan.getBoxCount()) {
            throw new IllegalArgumentException("송장 수가 박스 수(" + plan.getBoxCount() + ")와 일치해야 합니다");
        }

        LocalDateTime now = LocalDateTime.now(SEOUL);
        for (InvoiceEntry entry : invoices) {
            if (entry.boxNo() == null || entry.trackingNumber() == null || entry.trackingNumber().isBlank()) {
                throw new IllegalArgumentException("송장 항목이 올바르지 않습니다: " + entry);
            }
            CoupangInboundInvoice invoice = invoiceRepository.findByPlanIdAndBoxNo(planId, entry.boxNo())
                    .map(existing -> existing.toBuilder()
                            .trackingNumber(entry.trackingNumber())
                            .issuedAt(now)
                            .registeredAt(null) // 재발급 시 등록 상태 초기화
                            .build())
                    .orElse(CoupangInboundInvoice.builder()
                            .planId(planId)
                            .boxNo(entry.boxNo())
                            .trackingNumber(entry.trackingNumber())
                            .issuedAt(now)
                            .build());
            invoiceRepository.save(invoice);
        }

        planRepository.save(plan.toBuilder()
                .status(InboundPlanStatus.INVOICE_ISSUED)
                .updatedAt(now)
                .build());
        log.info("입고 송장 접수 planId={} 송장 {}건", planId, invoices.size());
        return InboundPlanStatus.INVOICE_ISSUED;
    }

    /** 회송지 설정 저장. WING 주소 목록(에이전트 동기화)에 있는 주소만 허용 — 자유 입력 금지. */
    @Transactional
    public void saveReturnAddress(String returnAddress) {
        if (returnAddress == null || returnAddress.isBlank()) {
            throw new IllegalArgumentException("회송지를 선택해주세요");
        }
        if (wingAddressRepository.findByAddressText(returnAddress).isEmpty()) {
            throw new AddressNotInWingException("WING에 등록되지 않은 주소입니다: " + returnAddress);
        }
        settingRepository.save(CoupangInboundSetting.builder()
                .settingKey(SETTING_RETURN_ADDRESS)
                .settingValue(returnAddress)
                .updatedAt(LocalDateTime.now(SEOUL))
                .build());
        log.info("회송지 설정 저장: {}", returnAddress);
    }

}
