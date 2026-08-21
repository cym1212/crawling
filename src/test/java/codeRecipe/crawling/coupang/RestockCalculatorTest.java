package codeRecipe.crawling.coupang;

import codeRecipe.crawling.crawling.coupang.RestockCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestockCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);

    @Test
    void 판매량이_없으면_제안하지_않는다() {
        assertTrue(RestockCalculator.calculate(5, null, 7, 21, TODAY).isEmpty());
        assertTrue(RestockCalculator.calculate(5, 0, 7, 21, TODAY).isEmpty());
    }

    @Test
    void 소진_임박_상품은_목표일수만큼_보충_제안한다() {
        // 재고 5, 30일 판매 30 → 일평균 1.00, 소진까지 5.0일
        RestockCalculator.Result result = RestockCalculator.calculate(5, 30, 7, 21, TODAY).orElseThrow();
        assertEquals(new BigDecimal("1.00"), result.dailyAvgSales());
        assertEquals(new BigDecimal("5.0"), result.daysUntilStockout());
        assertEquals(TODAY.plusDays(5), result.expectedStockoutDate());
        assertEquals(16, result.suggestedQuantity()); // ceil(1.00 * 21) - 5
    }

    @Test
    void 재고가_충분하면_제안하지_않는다() {
        // 재고 300, 일평균 1.00 → 300일 > 7일
        assertTrue(RestockCalculator.calculate(300, 30, 7, 21, TODAY).isEmpty());
    }

    @Test
    void 재고_0이면_즉시_소진으로_계산한다() {
        RestockCalculator.Result result = RestockCalculator.calculate(0, 30, 7, 21, TODAY).orElseThrow();
        assertEquals(new BigDecimal("0.0"), result.daysUntilStockout());
        assertEquals(TODAY, result.expectedStockoutDate());
        assertEquals(21, result.suggestedQuantity());
    }

    @Test
    void 제안_수량은_최소_1개다() {
        // 재고 0, 30일 판매 1 → 일평균 0.03, ceil(0.03 * 21) = ceil(0.63) = 1
        RestockCalculator.Result result = RestockCalculator.calculate(0, 1, 7, 21, TODAY).orElseThrow();
        assertEquals(1, result.suggestedQuantity());
    }

    @Test
    void 소진일이_임계값과_같으면_제안한다() {
        // 재고 7, 일평균 1.00 → 7.0일 == 임계 7일 → 제안 대상
        Optional<RestockCalculator.Result> result = RestockCalculator.calculate(7, 30, 7, 21, TODAY);
        assertTrue(result.isPresent());
        assertEquals(14, result.orElseThrow().suggestedQuantity()); // 21 - 7
    }

    @Test
    void 반올림_계산_검증() {
        // 30일 판매 10 → 일평균 0.33, 재고 2 → 2/0.33 = 6.06... → 6.1일 (HALF_UP scale 1)
        RestockCalculator.Result result = RestockCalculator.calculate(2, 10, 7, 21, TODAY).orElseThrow();
        assertEquals(new BigDecimal("0.33"), result.dailyAvgSales());
        assertEquals(new BigDecimal("6.1"), result.daysUntilStockout());
        assertEquals(5, result.suggestedQuantity()); // ceil(0.33 * 21) = ceil(6.93) = 7 - 2
    }
}
