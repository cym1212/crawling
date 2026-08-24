package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.domain.CoupangInventory;
import codeRecipe.crawling.crawling.domain.CoupangRestockSuggestion;
import codeRecipe.crawling.crawling.domain.CoupangSales;
import codeRecipe.crawling.crawling.domain.RestockStatus;
import codeRecipe.crawling.crawling.repository.CoupangInventoryRepository;
import codeRecipe.crawling.crawling.repository.CoupangRestockSuggestionRepository;
import codeRecipe.crawling.crawling.repository.CoupangSalesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private final CoupangSlackNotifier coupangSlackNotifier;

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
                boolean sent = coupangSlackNotifier.send(mismatchWarning);
                return new RestockRunResult(0, sent, mismatchWarning, List.of());
            }
            log.info("쿠팡 재입고 제안 없음");
            return new RestockRunResult(0, false, "재입고 제안 없음 (모든 상품 재고 충분 또는 판매 이력 없음)", List.of());
        }

        // 소진 임박 순 정렬 (급한 것이 카드 상단)
        computed.sort(Comparator.comparing(item -> item.saved().getDaysUntilStockout()));

        String fallbackText = buildFallbackText(computed);
        boolean sent = coupangSlackNotifier.sendCard(fallbackText, buildBlocks(computed, today));
        if (mismatchWarning != null) {
            coupangSlackNotifier.send(mismatchWarning); // 경고는 별도 텍스트 메시지로
        }
        String message = mismatchWarning != null ? fallbackText + "\n\n" + mismatchWarning : fallbackText;
        List<CoupangRestockSuggestion> suggestions = computed.stream().map(Computed::saved).toList();
        return new RestockRunResult(suggestions.size(), sent, message, suggestions);
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

    // ── 슬랙 Block Kit 카드 (확정 템플릿: 헤더 + 상품별 2×2 필드 + 옵션ID + 구분선 + 기준 푸터) ──

    private static final int MAX_CARD_ITEMS = 20; // Block Kit 50블록 제한 대비 상한
    private static final DateTimeFormatter CARD_DATE = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN);
    private static final BigDecimal URGENT_DAYS = BigDecimal.valueOf(3); // 🔴 기준

    private JSONArray buildBlocks(List<Computed> computed, LocalDate today) {
        JSONArray blocks = new JSONArray();
        blocks.put(new JSONObject().put("type", "header").put("text",
                new JSONObject().put("type", "plain_text").put("text", "📦 쿠팡 입고 제안").put("emoji", true)));
        blocks.put(contextBlock(today.format(CARD_DATE) + " · 총 " + computed.size() + "건"));
        blocks.put(divider());

        int shown = 0;
        for (Computed item : computed) {
            if (shown >= MAX_CARD_ITEMS) {
                break;
            }
            CoupangRestockSuggestion s = item.saved();
            RestockCalculator.Result basis = item.basis();
            String urgency = s.getDaysUntilStockout().compareTo(URGENT_DAYS) <= 0 ? "🔴" : "🟡";
            String name = escapeMrkdwn(s.getProductName() != null ? s.getProductName() : "(상품명 미매핑)");
            String title = urgency + " *" + name + "*" + (basis.surge() ? " 🔥급증" : "");

            JSONArray fields = new JSONArray()
                    .put(mrkdwn("입고 수량: `" + s.getSuggestedQuantity() + "개`"))
                    .put(mrkdwn("소진까지: `" + formatDays(s.getDaysUntilStockout()) + "일`"))
                    .put(mrkdwn("현재 재고: " + s.getCurrentQuantity() + "개"))
                    .put(mrkdwn("최근 30일 판매: " + basis.sales30Sum() + "개"));
            blocks.put(new JSONObject().put("type", "section").put("text", mrkdwn(title)).put("fields", fields));
            blocks.put(contextBlock("옵션ID " + s.getVendorItemId()));
            blocks.put(divider());
            shown++;
        }
        if (computed.size() > MAX_CARD_ITEMS) {
            blocks.put(contextBlock("외 " + (computed.size() - MAX_CARD_ITEMS) + "건 — 드라이런 API로 전체 확인 가능"));
        }
        blocks.put(contextBlock("기준: 소진 " + restockProperties.getThresholdDays() + "일 이내 알림 · "
                + restockProperties.getTargetDays() + "일치 보충"));
        return blocks;
    }

    /** 푸시 알림 미리보기/블록 미지원 클라이언트용 요약 텍스트 */
    private String buildFallbackText(List<Computed> computed) {
        StringBuilder text = new StringBuilder("📦 쿠팡 입고 제안 " + computed.size() + "건");
        for (Computed item : computed) {
            CoupangRestockSuggestion s = item.saved();
            text.append("\n· ").append(s.getProductName() != null ? s.getProductName() : "(상품명 미매핑)")
                    .append(" — ").append(s.getSuggestedQuantity()).append("개 입고 (소진까지 ")
                    .append(formatDays(s.getDaysUntilStockout())).append("일)");
        }
        return text.toString();
    }

    private static JSONObject mrkdwn(String text) {
        return new JSONObject().put("type", "mrkdwn").put("text", text);
    }

    private static JSONObject contextBlock(String text) {
        return new JSONObject().put("type", "context").put("elements", new JSONArray().put(mrkdwn(text)));
    }

    private static JSONObject divider() {
        return new JSONObject().put("type", "divider");
    }

    /** 슬랙 mrkdwn 이스케이프 (상품명의 &, <, > 대응) */
    private static String escapeMrkdwn(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 2.0 → "2", 5.5 → "5.5" */
    private static String formatDays(BigDecimal days) {
        return days.stripTrailingZeros().toPlainString();
    }
}
