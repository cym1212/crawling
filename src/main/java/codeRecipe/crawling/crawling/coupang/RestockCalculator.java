package codeRecipe.crawling.crawling.coupang;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

/**
 * 부족 재고 판단 순수 계산 로직 (Spring/DB 무관, 단위 테스트 대상).
 *
 * v2: 일별 판매 이력(coupang_sales) 기반.
 * - 창은 "어제까지"의 최근 7일/30일 (오늘 판매는 미완결이라 제외)
 * - 일평균 = max(7일 속도, 30일 속도) — 더 위험한(빠른) 신호를 채택
 * - 신상품 보정: 첫 판매일이 창 길이보다 최근이면 실제 판매 경과일로 나눔
 * - 일별 데이터가 없으면 재고 API의 30일 판매량으로 폴백 (v1 동작)
 */
public final class RestockCalculator {

    private RestockCalculator() {
    }

    public record Result(BigDecimal dailyAvgSales, BigDecimal daysUntilStockout,
                         LocalDate expectedStockoutDate, int suggestedQuantity,
                         BigDecimal speed7, BigDecimal speed30, boolean surge, boolean fallback,
                         int sales30Sum) {
    }

    /**
     * @param dailySales             일별 판매 수량 (최근 30일 창 내의 날짜만, 판매 없는 날 생략 가능)
     * @param firstSaleDate          이 상품의 최초 판매일 (이력 전체 기준, 모르면 null = 기존 상품 취급)
     * @param apiSalesCountLast30Days 재고 API가 주는 30일 판매량 (일별 데이터 없을 때 폴백)
     * @return 판매 이력이 없거나 재고가 임계일수보다 넉넉하면 empty
     */
    public static Optional<Result> calculate(int currentQuantity,
                                             Map<LocalDate, Integer> dailySales,
                                             LocalDate firstSaleDate,
                                             Integer apiSalesCountLast30Days,
                                             int thresholdDays, int targetDays, LocalDate today) {
        BigDecimal speed7;
        BigDecimal speed30;
        boolean surge = false;
        boolean fallback = false;
        int sales30Sum;

        if (dailySales == null || dailySales.isEmpty()) {
            // 폴백: 일별 데이터 없음 → v1 방식 (30일 합계 ÷ 30)
            if (apiSalesCountLast30Days == null || apiSalesCountLast30Days <= 0) {
                return Optional.empty();
            }
            speed30 = BigDecimal.valueOf(apiSalesCountLast30Days)
                    .divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
            speed7 = speed30;
            fallback = true;
            sales30Sum = apiSalesCountLast30Days;
        } else {
            LocalDate windowEnd = today.minusDays(1);
            int sum30 = sumRange(dailySales, today.minusDays(30), windowEnd);
            int sum7 = sumRange(dailySales, today.minusDays(7), windowEnd);
            int prev7 = sumRange(dailySales, today.minusDays(14), today.minusDays(8));

            if (sum30 <= 0) {
                return Optional.empty(); // 최근 30일 판매 없음
            }
            sales30Sum = sum30;

            // 신상품 보정: 판매 시작 후 경과일이 창보다 짧으면 경과일로 나눔
            long daysSinceFirstSale = firstSaleDate == null ? 30
                    : Math.max(1, ChronoUnit.DAYS.between(firstSaleDate, today));
            long days30 = Math.min(30, daysSinceFirstSale);
            long days7 = Math.min(7, daysSinceFirstSale);

            speed30 = BigDecimal.valueOf(sum30).divide(BigDecimal.valueOf(days30), 2, RoundingMode.HALF_UP);
            speed7 = BigDecimal.valueOf(sum7).divide(BigDecimal.valueOf(days7), 2, RoundingMode.HALF_UP);

            // 급증: 최근 7일이 그 이전 7일의 2배 이상 (이전 7일이 0이면 2개 이상 판매 시)
            surge = prev7 > 0 ? sum7 >= prev7 * 2 && sum7 >= 2 : sum7 >= 2;
        }

        BigDecimal dailyAvg = speed7.max(speed30);
        if (dailyAvg.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        BigDecimal daysUntilStockout = BigDecimal.valueOf(currentQuantity)
                .divide(dailyAvg, 1, RoundingMode.HALF_UP);
        if (daysUntilStockout.compareTo(BigDecimal.valueOf(thresholdDays)) > 0) {
            return Optional.empty();
        }
        LocalDate expectedStockoutDate = today.plusDays(daysUntilStockout.longValue());
        int suggested = dailyAvg.multiply(BigDecimal.valueOf(targetDays))
                .setScale(0, RoundingMode.CEILING)
                .intValue() - currentQuantity;
        return Optional.of(new Result(dailyAvg, daysUntilStockout, expectedStockoutDate,
                Math.max(1, suggested), speed7, speed30, surge, fallback, sales30Sum));
    }

    private static int sumRange(Map<LocalDate, Integer> dailySales, LocalDate from, LocalDate to) {
        int sum = 0;
        for (Map.Entry<LocalDate, Integer> entry : dailySales.entrySet()) {
            LocalDate date = entry.getKey();
            if (!date.isBefore(from) && !date.isAfter(to) && entry.getValue() != null) {
                sum += entry.getValue();
            }
        }
        return sum;
    }
}
