package codeRecipe.crawling.crawling.coupang;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/** 부족 재고 판단 순수 계산 로직 (Spring/DB 무관, 단위 테스트 대상) */
public final class RestockCalculator {

    private RestockCalculator() {
    }

    public record Result(BigDecimal dailyAvgSales, BigDecimal daysUntilStockout,
                         LocalDate expectedStockoutDate, int suggestedQuantity) {
    }

    /**
     * @return 판매 이력이 없거나(일평균 0) 재고가 임계일수보다 넉넉하면 empty
     */
    public static Optional<Result> calculate(int currentQuantity, Integer salesCountLast30Days,
                                             int thresholdDays, int targetDays, LocalDate today) {
        if (salesCountLast30Days == null || salesCountLast30Days <= 0) {
            return Optional.empty();
        }
        BigDecimal dailyAvg = BigDecimal.valueOf(salesCountLast30Days)
                .divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP); // 판매 1건이면 0.03 → 0이 될 수 없음
        BigDecimal daysUntilStockout = BigDecimal.valueOf(currentQuantity)
                .divide(dailyAvg, 1, RoundingMode.HALF_UP);
        if (daysUntilStockout.compareTo(BigDecimal.valueOf(thresholdDays)) > 0) {
            return Optional.empty();
        }
        LocalDate expectedStockoutDate = today.plusDays(daysUntilStockout.longValue());
        int suggested = dailyAvg.multiply(BigDecimal.valueOf(targetDays))
                .setScale(0, RoundingMode.CEILING)
                .intValue() - currentQuantity;
        return Optional.of(new Result(dailyAvg, daysUntilStockout, expectedStockoutDate, Math.max(1, suggested)));
    }
}
