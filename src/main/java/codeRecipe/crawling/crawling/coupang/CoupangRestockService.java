package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.SlackWebhookService;
import codeRecipe.crawling.crawling.domain.CoupangInventory;
import codeRecipe.crawling.crawling.domain.CoupangRestockSuggestion;
import codeRecipe.crawling.crawling.domain.CoupangSales;
import codeRecipe.crawling.crawling.domain.RestockStatus;
import codeRecipe.crawling.crawling.repository.CoupangInventoryRepository;
import codeRecipe.crawling.crawling.repository.CoupangRestockSuggestionRepository;
import codeRecipe.crawling.crawling.repository.CoupangSalesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 최신 재고 스냅샷 + 일별 판매 이력(coupang_sales) 기반 부족 재고 판단(v2)
 * → coupang_restock_suggestion 생성 → 슬랙 알림.
 * 일평균 = max(7일 속도, 30일 속도), 신상품 보정, 급증(🔥) 표시.
 * 일별 데이터가 없는 상품은 재고 API의 30일 판매량으로 폴백(v1 방식).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangRestockService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CoupangInventoryRepository coupangInventoryRepository;
    private final CoupangRestockSuggestionRepository suggestionRepository;
    private final CoupangSalesRepository coupangSalesRepository;
    private final CoupangRestockProperties restockProperties;
    private final SlackWebhookService slackWebhookService;

    /** 수동 트리거/스케줄 실행 결과 (제안 상세 목록 포함) */
    public record RestockRunResult(int suggestionCount, boolean slackSent, String slackMessage,
                                   List<CoupangRestockSuggestion> suggestions) {
    }

    private record Computed(CoupangRestockSuggestion saved, RestockCalculator.Result basis) {
    }

    /** 드라이런 결과 항목 (저장/슬랙 없이 계산 근거까지 반환) */
    public record SimulatedSuggestion(Long vendorItemId, String productName, int currentQuantity,
                                      java.math.BigDecimal dailyAvgSales, java.math.BigDecimal speed7,
                                      java.math.BigDecimal speed30, boolean surge, boolean fallback,
                                      java.math.BigDecimal daysUntilStockout, LocalDate expectedStockoutDate,
                                      int suggestedQuantity) {
    }

    /** 계산에 필요한 데이터 묶음 */
    private record CalcContext(List<CoupangInventory> snapshot,
                               Map<Long, Map<LocalDate, Integer>> dailyByItem,
                               Map<Long, LocalDate> firstSaleByItem,
                               LocalDate today) {
    }

    private CalcContext loadContext() {
        LocalDateTime maxCollectedAt = coupangInventoryRepository.findMaxCollectedAt();
        if (maxCollectedAt == null) {
            return null;
        }
        // 마지막 동기화 시점 스냅샷만 사용 — 판매 종료 등으로 갱신이 끊긴 오래된 행 제외
        List<CoupangInventory> snapshot = coupangInventoryRepository.findByCollectedAt(maxCollectedAt);
        LocalDate today = LocalDate.now(SEOUL);

        // 일별 판매 이력 로드 (어제까지 최근 30일) + 옵션별 최초 판매일
        Map<Long, Map<LocalDate, Integer>> dailyByItem = new HashMap<>();
        for (CoupangSales sales : coupangSalesRepository.findBySalesDateBetween(today.minusDays(30), today.minusDays(1))) {
            dailyByItem.computeIfAbsent(sales.getVendorItemId(), k -> new HashMap<>())
                    .put(sales.getSalesDate(), sales.getQuantity());
        }
        Map<Long, LocalDate> firstSaleByItem = new HashMap<>();
        for (Object[] row : coupangSalesRepository.findFirstSaleDates()) {
            firstSaleByItem.put((Long) row[0], (LocalDate) row[1]);
        }
        return new CalcContext(snapshot, dailyByItem, firstSaleByItem, today);
    }

    /**
     * 드라이런: 저장/슬랙/중복체크 없이 지정 기준으로 계산 결과만 반환.
     * 임계·목표 일수를 바꿔가며 실데이터로 실험하는 용도.
     */
    public List<SimulatedSuggestion> simulate(int thresholdDays, int targetDays) {
        CalcContext context = loadContext();
        if (context == null) {
            return List.of();
        }
        List<SimulatedSuggestion> results = new ArrayList<>();
        for (CoupangInventory inventory : context.snapshot()) {
            RestockCalculator.calculate(
                    inventory.getOrderableQuantity(),
                    context.dailyByItem().getOrDefault(inventory.getVendorItemId(), Map.of()),
                    context.firstSaleByItem().get(inventory.getVendorItemId()),
                    inventory.getSalesCountLast30Days(),
                    thresholdDays, targetDays, context.today()
            ).ifPresent(r -> results.add(new SimulatedSuggestion(
                    inventory.getVendorItemId(), inventory.getProductName(), inventory.getOrderableQuantity(),
                    r.dailyAvgSales(), r.speed7(), r.speed30(), r.surge(), r.fallback(),
                    r.daysUntilStockout(), r.expectedStockoutDate(), r.suggestedQuantity())));
        }
        results.sort((a, b) -> a.daysUntilStockout().compareTo(b.daysUntilStockout()));
        return results;
    }

    public RestockRunResult generateAndNotify() {
        CalcContext context = loadContext();
        if (context == null) {
            log.warn("쿠팡 재고 데이터가 없어 재입고 제안을 건너뜁니다.");
            return new RestockRunResult(0, false, "재고 데이터 없음", List.of());
        }
        List<CoupangInventory> snapshot = context.snapshot();
        Map<Long, Map<LocalDate, Integer>> dailyByItem = context.dailyByItem();
        Map<Long, LocalDate> firstSaleByItem = context.firstSaleByItem();
        LocalDate today = context.today();

        List<Computed> computed = new ArrayList<>();
        for (CoupangInventory inventory : snapshot) {
            Optional<RestockCalculator.Result> result = RestockCalculator.calculate(
                    inventory.getOrderableQuantity(),
                    dailyByItem.getOrDefault(inventory.getVendorItemId(), Map.of()),
                    firstSaleByItem.get(inventory.getVendorItemId()),
                    inventory.getSalesCountLast30Days(),
                    restockProperties.getThresholdDays(),
                    restockProperties.getTargetDays(),
                    today);
            if (result.isEmpty()) {
                continue;
            }
            if (suggestionRepository.existsByVendorItemIdAndSuggestionDate(inventory.getVendorItemId(), today)) {
                continue; // 오늘자 제안이 이미 있음
            }
            RestockCalculator.Result r = result.get();
            try {
                CoupangRestockSuggestion saved = suggestionRepository.save(CoupangRestockSuggestion.builder()
                        .vendorItemId(inventory.getVendorItemId())
                        .productName(inventory.getProductName())
                        .currentQuantity(inventory.getOrderableQuantity())
                        .dailyAvgSales(r.dailyAvgSales())
                        .daysUntilStockout(r.daysUntilStockout())
                        .expectedStockoutDate(r.expectedStockoutDate())
                        .suggestedQuantity(r.suggestedQuantity())
                        .status(RestockStatus.SUGGESTED)
                        .suggestionDate(today)
                        .createdAt(LocalDateTime.now(SEOUL))
                        .build());
                computed.add(new Computed(saved, r));
            } catch (DataIntegrityViolationException e) {
                log.warn("재입고 제안 중복 저장 스킵 vendorItemId={} date={}", inventory.getVendorItemId(), today);
            }
        }
        log.info("쿠팡 재입고 제안 생성 완료 {}건", computed.size());

        // 데이터 불일치 감지: 쿠팡 집계(재고 API) 대비 자체 수집이 절반 미만이면 수집 파이프라인 점검 경고
        String mismatchWarning = buildMismatchWarning(snapshot, dailyByItem);

        if (computed.isEmpty()) {
            if (mismatchWarning != null) {
                slackWebhookService.sendMessage(mismatchWarning);
                return new RestockRunResult(0, true, mismatchWarning, List.of());
            }
            log.info("쿠팡 재입고 제안 없음");
            return new RestockRunResult(0, false, "재입고 제안 없음 (모든 상품 재고 충분 또는 판매 이력 없음)", List.of());
        }

        String message = buildSlackMessage(computed);
        if (mismatchWarning != null) {
            message = message + "\n\n" + mismatchWarning;
        }
        slackWebhookService.sendMessage(message);
        List<CoupangRestockSuggestion> suggestions = computed.stream().map(Computed::saved).toList();
        return new RestockRunResult(suggestions.size(), true, message, suggestions);
    }

    private String buildMismatchWarning(List<CoupangInventory> snapshot,
                                        Map<Long, Map<LocalDate, Integer>> dailyByItem) {
        int apiTotal = 0;
        for (CoupangInventory inventory : snapshot) {
            apiTotal += inventory.getSalesCountLast30Days() == null ? 0 : inventory.getSalesCountLast30Days();
        }
        int oursTotal = 0;
        for (Map<LocalDate, Integer> daily : dailyByItem.values()) {
            for (Integer quantity : daily.values()) {
                oursTotal += quantity == null ? 0 : quantity;
            }
        }
        if (apiTotal >= 5 && oursTotal * 2 < apiTotal) {
            return String.format("⚠️ [쿠팡] 판매 수집 점검 필요: 쿠팡 집계 최근 30일 %,d개 vs 자체 수집 %,d개 — 주문 수집 파이프라인 확인 요망",
                    apiTotal, oursTotal);
        }
        return null;
    }

    private String buildSlackMessage(List<Computed> computed) {
        StringBuilder message = new StringBuilder();
        message.append("📦 *쿠팡 로켓그로스 재입고 제안* (")
                .append(computed.get(0).saved().getSuggestionDate())
                .append(")\n\n");
        int index = 1;
        for (Computed item : computed) {
            CoupangRestockSuggestion s = item.saved();
            RestockCalculator.Result basis = item.basis();
            String name = s.getProductName() != null ? s.getProductName() : "(상품명 미매핑)";
            message.append(String.format("%d. %s (옵션ID: %d)%s\n", index++, name, s.getVendorItemId(),
                    basis.surge() ? " 🔥급증" : ""));
            String basisText = basis.fallback()
                    ? "쿠팡 30일 집계 기준"
                    : String.format("7일 속도 %s / 30일 속도 %s", basis.speed7(), basis.speed30());
            message.append(String.format("   현재 재고 %,d개 / 일평균 판매 %s개 (%s)\n",
                    s.getCurrentQuantity(), s.getDailyAvgSales(), basisText));
            message.append(String.format("   소진까지 약 %s일 (%s) → 제안 입고 수량 %,d개\n\n",
                    s.getDaysUntilStockout(), s.getExpectedStockoutDate(), s.getSuggestedQuantity()));
        }
        message.append(String.format("총 %d건 / 기준: 소진 %d일 이내 제안, %d일치 보충 (일평균 = 7일·30일 속도 중 빠른 쪽)",
                computed.size(), restockProperties.getThresholdDays(), restockProperties.getTargetDays()));
        return message.toString();
    }
}
