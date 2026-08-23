package codeRecipe.crawling.coupang;

import codeRecipe.crawling.crawling.coupang.RestockCalculator;
import codeRecipe.crawling.crawling.coupang.RestockCalculator.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestockCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 23);
    private static final LocalDate OLD_FIRST_SALE = TODAY.minusDays(200); // 기존 상품

    /** daysAgo(1)=어제 ... 형태로 일별 판매 맵 구성 */
    private static Map<LocalDate, Integer> daily(int[][] daysAgoAndQty) {
        Map<LocalDate, Integer> map = new HashMap<>();
        for (int[] pair : daysAgoAndQty) {
            map.put(TODAY.minusDays(pair[0]), pair[1]);
        }
        return map;
    }

    // ── 폴백 (일별 데이터 없음 → v1 방식) ──────────────────────────────

    @Test
    void 일별_데이터도_API_판매량도_없으면_제안하지_않는다() {
        assertTrue(RestockCalculator.calculate(5, Map.of(), null, null, 7, 21, TODAY).isEmpty());
        assertTrue(RestockCalculator.calculate(5, Map.of(), null, 0, 7, 21, TODAY).isEmpty());
    }

    @Test
    void 폴백_계산은_v1과_동일하다() {
        // 재고 5, API 30일 판매 30 → 일평균 1.00, 소진 5.0일, 제안 ceil(21)-5=16
        Result result = RestockCalculator.calculate(5, Map.of(), null, 30, 7, 21, TODAY).orElseThrow();
        assertTrue(result.fallback());
        assertEquals(new BigDecimal("1.00"), result.dailyAvgSales());
        assertEquals(new BigDecimal("5.0"), result.daysUntilStockout());
        assertEquals(16, result.suggestedQuantity());
    }

    @Test
    void 폴백에서도_재고가_충분하면_제안하지_않는다() {
        assertTrue(RestockCalculator.calculate(300, Map.of(), null, 30, 7, 21, TODAY).isEmpty());
    }

    // ── v2: 일별 데이터 기반 ──────────────────────────────────────────

    @Test
    void 꾸준한_판매는_7일과_30일_속도가_같아_v1과_동일한_결과다() {
        // 매일 1개씩 30일 (재고 300 → 소진 300일 → 제안 없음)
        int[][] steady = new int[30][2];
        for (int i = 0; i < 30; i++) {
            steady[i] = new int[]{i + 1, 1};
        }
        assertTrue(RestockCalculator.calculate(300, daily(steady), OLD_FIRST_SALE, null, 7, 21, TODAY).isEmpty());

        // 재고 5개면 소진 5일 → 제안 (v1과 동일 수치)
        Result result = RestockCalculator.calculate(5, daily(steady), OLD_FIRST_SALE, null, 7, 21, TODAY).orElseThrow();
        assertEquals(new BigDecimal("1.00"), result.dailyAvgSales());
        assertEquals(16, result.suggestedQuantity());
        assertFalse(result.surge());
        assertFalse(result.fallback());
    }

    @Test
    void 급증_상품은_7일_속도를_채택해_즉시_제안한다() {
        // 8~14일 전: 매일 1개(=7개), 최근 1~3일 전: 매일 5개, 4~7일 전: 매일 1개 → 7일 합 19
        Map<LocalDate, Integer> sales = daily(new int[][]{
                {1, 5}, {2, 5}, {3, 5}, {4, 1}, {5, 1}, {6, 1}, {7, 1},
                {8, 1}, {9, 1}, {10, 1}, {11, 1}, {12, 1}, {13, 1}, {14, 1},
        });
        // 30일 속도 = 26/30 = 0.87, 7일 속도 = 19/7 = 2.71 → 2.71 채택
        Result result = RestockCalculator.calculate(15, sales, OLD_FIRST_SALE, null, 7, 21, TODAY).orElseThrow();
        assertEquals(new BigDecimal("2.71"), result.speed7());
        assertEquals(new BigDecimal("0.87"), result.speed30());
        assertEquals(new BigDecimal("2.71"), result.dailyAvgSales());
        assertEquals(new BigDecimal("5.5"), result.daysUntilStockout()); // 15 / 2.71
        assertEquals(42, result.suggestedQuantity()); // ceil(2.71×21)=57 − 15
        assertTrue(result.surge()); // 19 >= 2×7
    }

    @Test
    void 신상품은_실제_판매_경과일로_나눈다() {
        // 첫 판매 3일 전, 3일간 5+5+4=14개 판매 → 속도 14/3 = 4.67
        Map<LocalDate, Integer> sales = daily(new int[][]{{1, 4}, {2, 5}, {3, 5}});
        Result result = RestockCalculator.calculate(10, sales, TODAY.minusDays(3), null, 7, 21, TODAY).orElseThrow();
        assertEquals(new BigDecimal("4.67"), result.speed7());
        assertEquals(new BigDecimal("4.67"), result.speed30());
        assertEquals(new BigDecimal("2.1"), result.daysUntilStockout()); // 10 / 4.67
        assertEquals(89, result.suggestedQuantity()); // ceil(4.67×21)=99 − 10
    }

    @Test
    void 판매가_식으면_30일_속도가_채택되어_보수적으로_계산한다() {
        // 예전(8~30일 전)에 매일 2개씩 팔리다 최근 7일은 판매 0
        int[][] cooled = new int[23][2];
        for (int i = 0; i < 23; i++) {
            cooled[i] = new int[]{i + 8, 2};
        }
        // 7일 속도 0, 30일 속도 = 46/30 = 1.53 → 1.53 채택
        Result result = RestockCalculator.calculate(5, daily(cooled), OLD_FIRST_SALE, null, 7, 21, TODAY).orElseThrow();
        assertEquals(new BigDecimal("1.53"), result.dailyAvgSales());
        assertFalse(result.surge());
    }

    @Test
    void 소진일이_임계값과_같으면_제안한다() {
        // 매일 1개 × 30일, 재고 7 → 소진 7.0일 == 임계 7
        int[][] steady = new int[30][2];
        for (int i = 0; i < 30; i++) {
            steady[i] = new int[]{i + 1, 1};
        }
        Optional<Result> result = RestockCalculator.calculate(7, daily(steady), OLD_FIRST_SALE, null, 7, 21, TODAY);
        assertTrue(result.isPresent());
        assertEquals(14, result.orElseThrow().suggestedQuantity()); // 21 − 7
    }

    @Test
    void 오늘_판매는_미완결이라_계산에서_제외한다() {
        // 판매가 "오늘"에만 있으면 창(어제까지) 밖이라 제안 없음
        Map<LocalDate, Integer> onlyToday = Map.of(TODAY, 10);
        assertTrue(RestockCalculator.calculate(5, onlyToday, TODAY, null, 7, 21, TODAY).isEmpty());
    }
}
