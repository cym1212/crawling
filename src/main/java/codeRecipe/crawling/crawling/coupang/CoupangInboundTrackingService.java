package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.domain.CoupangInboundPlan;
import codeRecipe.crawling.crawling.domain.CoupangInboundPlanItem;
import codeRecipe.crawling.crawling.domain.CoupangRestockSuggestion;
import codeRecipe.crawling.crawling.domain.CoupangSales;
import codeRecipe.crawling.crawling.domain.InboundPlanStatus;
import codeRecipe.crawling.crawling.domain.RestockStatus;
import codeRecipe.crawling.crawling.repository.CoupangInboundPlanItemRepository;
import codeRecipe.crawling.crawling.repository.CoupangInboundPlanRepository;
import codeRecipe.crawling.crawling.repository.CoupangInventoryRepository;
import codeRecipe.crawling.crawling.repository.CoupangRestockSuggestionRepository;
import codeRecipe.crawling.crawling.repository.CoupangSalesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * 입고 반영 추적 배치 (의뢰 #8).
 * 재고 스냅샷 증가분 + 제출 이후 판매량으로 반영 수량을 추정한다:
 *   추정 반영 = max(0, 현재 재고 - 제출 시점 재고 + 제출 이후 판매량)
 * 전 품목이 신청 수량 이상 반영되면 COMPLETED, 도착예정일 + 유예일이 지나도 미달이면 MISMATCH.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangInboundTrackingService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CoupangInboundPlanRepository planRepository;
    private final CoupangInboundPlanItemRepository itemRepository;
    private final CoupangInventoryRepository inventoryRepository;
    private final CoupangSalesRepository salesRepository;
    private final CoupangRestockSuggestionRepository suggestionRepository;
    private final CoupangSlackNotifier slackNotifier;

    @Value("${coupang.inbound.mismatch-grace-days:7}")
    private int mismatchGraceDays;

    @Transactional
    public void trackReceipts() {
        List<CoupangInboundPlan> plans = planRepository.findByStatusInOrderByRequestedAtAsc(Set.of(
                InboundPlanStatus.SUBMITTED, InboundPlanStatus.INVOICE_ISSUED, InboundPlanStatus.INVOICE_REGISTERED));
        LocalDate today = LocalDate.now(SEOUL);

        for (CoupangInboundPlan plan : plans) {
            if (plan.getSubmittedAt() == null) {
                continue;
            }
            List<CoupangInboundPlanItem> items = itemRepository.findByPlanId(plan.getPlanId());
            if (items.isEmpty()) {
                continue;
            }

            boolean allAccepted = true;
            StringBuilder mismatchDetail = new StringBuilder();
            LocalDate submittedDate = plan.getSubmittedAt().toLocalDate();

            for (CoupangInboundPlanItem item : items) {
                int estimated = estimateAccepted(item, submittedDate, today);
                int accepted = Math.min(estimated, item.getQuantity());
                itemRepository.save(item.toBuilder().acceptedQuantity(accepted).build());
                if (estimated < item.getQuantity()) {
                    allAccepted = false;
                    mismatchDetail.append("\n- ").append(item.getProductName() == null ? item.getVendorItemId() : item.getProductName())
                            .append(": 신청 ").append(item.getQuantity()).append(" / 반영 추정 ").append(accepted);
                }
            }

            if (allAccepted) {
                complete(plan, items, today);
            } else if (plan.getArrivalDate() != null
                    && today.isAfter(plan.getArrivalDate().plusDays(mismatchGraceDays))) {
                markMismatch(plan, mismatchDetail.toString());
            }
        }
    }

    /** 추정 반영 수량 = max(0, 현재 재고 - 기준선 + 제출 이후 판매량). 재고 스냅샷이 없으면 0. */
    private int estimateAccepted(CoupangInboundPlanItem item, LocalDate submittedDate, LocalDate today) {
        Integer current = inventoryRepository.findByVendorItemId(item.getVendorItemId())
                .map(inventory -> inventory.getOrderableQuantity())
                .orElse(null);
        if (current == null) {
            return 0;
        }
        int baseline = item.getBaselineQuantity() == null ? 0 : item.getBaselineQuantity();
        int soldSince = salesRepository
                .findByVendorItemIdAndSalesDateBetween(item.getVendorItemId(), submittedDate, today)
                .stream()
                .mapToInt(CoupangSales::getQuantity)
                .sum();
        return Math.max(0, current - baseline + soldSince);
    }

    private void complete(CoupangInboundPlan plan, List<CoupangInboundPlanItem> items, LocalDate today) {
        var now = java.time.LocalDateTime.now(SEOUL);
        planRepository.save(plan.toBuilder()
                .status(InboundPlanStatus.COMPLETED)
                .completedAt(now)
                .updatedAt(now)
                .build());

        List<Long> vendorItemIds = items.stream().map(CoupangInboundPlanItem::getVendorItemId).toList();
        List<CoupangRestockSuggestion> suggestions =
                suggestionRepository.findByVendorItemIdInAndStatus(vendorItemIds, RestockStatus.REQUESTED);
        for (CoupangRestockSuggestion suggestion : suggestions) {
            suggestionRepository.save(suggestion.toBuilder().status(RestockStatus.COMPLETED).build());
        }

        long leadDays = ChronoUnit.DAYS.between(plan.getSubmittedAt().toLocalDate(), today);
        int totalQuantity = items.stream().mapToInt(CoupangInboundPlanItem::getQuantity).sum();
        slackNotifier.send("🎉 [로켓그로스 입고] 입고 완료 — 입고 ID " + plan.getWingInboundId()
                + "\n" + plan.getFulfillmentCenter() + " · 총 " + totalQuantity + "개 전량 반영 (제출 후 " + leadDays + "일)");
        log.info("입고 완료 planId={} wingInboundId={} 소요 {}일", plan.getPlanId(), plan.getWingInboundId(), leadDays);
    }

    private void markMismatch(CoupangInboundPlan plan, String detail) {
        var now = java.time.LocalDateTime.now(SEOUL);
        planRepository.save(plan.toBuilder()
                .status(InboundPlanStatus.MISMATCH)
                .updatedAt(now)
                .build());
        slackNotifier.send("⚠️ [로켓그로스 입고] 수량 불일치 — 입고 ID " + plan.getWingInboundId()
                + " (도착예정일 + " + mismatchGraceDays + "일 경과)" + detail
                + "\nWING 입고 상세에서 검수 결과를 확인해주세요.");
        log.warn("입고 수량 불일치 planId={} wingInboundId={}", plan.getPlanId(), plan.getWingInboundId());
    }
}
