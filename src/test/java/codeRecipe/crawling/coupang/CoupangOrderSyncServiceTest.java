package codeRecipe.crawling.coupang;

import codeRecipe.crawling.crawling.coupang.CoupangOrderSyncService;
import codeRecipe.crawling.crawling.coupang.CoupangOrderSyncService.OrderAggregate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoupangOrderSyncServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode json(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    private static long epochMillis(int year, int month, int day, int hour) {
        return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, SEOUL).toInstant().toEpochMilli();
    }

    @Test
    void 결제일과_옵션별로_집계한다_단가는_소수점_문자열도_파싱한다() throws Exception {
        long aug7 = epochMillis(2026, 8, 7, 10);
        long aug8 = epochMillis(2026, 8, 8, 15);
        JsonNode data = json(String.format("""
                [
                  {"orderId": 1, "paidAt": %d, "orderItems": [
                    {"vendorItemId": 100, "productName": "고래 공책", "salesQuantity": 2, "unitSalesPrice": "4900.0"}
                  ]},
                  {"orderId": 2, "paidAt": %d, "orderItems": [
                    {"vendorItemId": 100, "productName": "고래 공책", "salesQuantity": 1, "unitSalesPrice": "4900.0"},
                    {"vendorItemId": 200, "productName": "머리끈", "salesQuantity": 1, "unitSalesPrice": "3000.0"}
                  ]}
                ]
                """, aug7, aug8));

        Map<LocalDate, Map<Long, OrderAggregate>> result =
                CoupangOrderSyncService.aggregateOrders(data, new TreeMap<>());

        assertEquals(2, result.size());
        Map<Long, OrderAggregate> day7 = result.get(LocalDate.of(2026, 8, 7));
        assertEquals(2, day7.get(100L).getQuantity());
        assertEquals(9800L, day7.get(100L).getAmount());      // 2 × 4900 (문자열 "4900.0" 파싱)
        assertEquals("고래 공책", day7.get(100L).getProductName());

        Map<Long, OrderAggregate> day8 = result.get(LocalDate.of(2026, 8, 8));
        assertEquals(1, day8.get(100L).getQuantity());
        assertEquals(3000L, day8.get(200L).getAmount());
    }

    @Test
    void 같은_날_같은_옵션의_여러_주문을_합산한다() throws Exception {
        long morning = epochMillis(2026, 8, 7, 9);
        long evening = epochMillis(2026, 8, 7, 21);
        JsonNode data = json(String.format("""
                [
                  {"paidAt": %d, "orderItems": [{"vendorItemId": 100, "salesQuantity": 1, "unitSalesPrice": "1000.0"}]},
                  {"paidAt": %d, "orderItems": [{"vendorItemId": 100, "salesQuantity": 2, "unitSalesPrice": "1000.0"}]}
                ]
                """, morning, evening));

        Map<LocalDate, Map<Long, OrderAggregate>> result =
                CoupangOrderSyncService.aggregateOrders(data, new TreeMap<>());

        assertEquals(1, result.size());
        assertEquals(3, result.get(LocalDate.of(2026, 8, 7)).get(100L).getQuantity());
        assertEquals(3000L, result.get(LocalDate.of(2026, 8, 7)).get(100L).getAmount());
    }

    @Test
    void 자정_직전_결제는_한국시간_기준_날짜로_버킷팅된다() throws Exception {
        // 한국시간 8/7 23:30 = UTC 8/7 14:30 — UTC 기준으로도 8/7이지만,
        // 한국시간 8/8 00:30 = UTC 8/7 15:30 — UTC로 날짜를 자르면 8/7로 잘못 분류되는 케이스
        long kst0030 = ZonedDateTime.of(2026, 8, 8, 0, 30, 0, 0, SEOUL).toInstant().toEpochMilli();
        JsonNode data = json(String.format("""
                [{"paidAt": %d, "orderItems": [{"vendorItemId": 100, "salesQuantity": 1, "unitSalesPrice": "1000.0"}]}]
                """, kst0030));

        Map<LocalDate, Map<Long, OrderAggregate>> result =
                CoupangOrderSyncService.aggregateOrders(data, new TreeMap<>());

        assertTrue(result.containsKey(LocalDate.of(2026, 8, 8))); // 한국시간 기준 8/8
    }

    @Test
    void paidAt이나_vendorItemId가_없으면_건너뛴다() throws Exception {
        long aug7 = epochMillis(2026, 8, 7, 10);
        JsonNode data = json(String.format("""
                [
                  {"orderItems": [{"vendorItemId": 100, "salesQuantity": 1, "unitSalesPrice": "1000.0"}]},
                  {"paidAt": %d, "orderItems": [
                    {"vendorItemId": null, "salesQuantity": 1, "unitSalesPrice": "1000.0"},
                    {"salesQuantity": 1, "unitSalesPrice": "1000.0"}
                  ]}
                ]
                """, aug7));

        Map<LocalDate, Map<Long, OrderAggregate>> result =
                CoupangOrderSyncService.aggregateOrders(data, new TreeMap<>());
        assertTrue(result.isEmpty());
    }

    @Test
    void paidAt이_숫자_문자열이어도_파싱한다() throws Exception {
        long aug7 = epochMillis(2026, 8, 7, 10);
        JsonNode data = json(String.format("""
                [{"paidAt": "%d", "orderItems": [{"vendorItemId": 100, "salesQuantity": 1, "unitSalesPrice": "1000.0"}]}]
                """, aug7));

        Map<LocalDate, Map<Long, OrderAggregate>> result =
                CoupangOrderSyncService.aggregateOrders(data, new TreeMap<>());
        assertEquals(1, result.get(LocalDate.of(2026, 8, 7)).get(100L).getQuantity());
    }
}
