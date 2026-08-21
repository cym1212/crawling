package codeRecipe.crawling.coupang;

import codeRecipe.crawling.crawling.coupang.CoupangOrderSyncService;
import codeRecipe.crawling.crawling.coupang.CoupangOrderSyncService.OrderAggregate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoupangOrderSyncServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode json(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    @Test
    void 주문_항목을_옵션별로_집계한다() throws Exception {
        JsonNode data = json("""
                [
                  {"orderId": 1, "orderItems": [
                    {"vendorItemId": 100, "productName": "고래 공책", "salesQuantity": 2, "unitSalesPrice": 5000},
                    {"vendorItemId": 200, "productName": "머리끈", "salesQuantity": 1, "unitSalesPrice": 3000}
                  ]},
                  {"orderId": 2, "orderItems": [
                    {"vendorItemId": 100, "productName": "고래 공책", "salesQuantity": 3, "unitSalesPrice": 5000}
                  ]}
                ]
                """);
        Map<Long, OrderAggregate> result = CoupangOrderSyncService.aggregatePage(data, new LinkedHashMap<>());

        assertEquals(2, result.size());
        assertEquals(5, result.get(100L).getQuantity());          // 2 + 3
        assertEquals(25000L, result.get(100L).getAmount());       // 2*5000 + 3*5000
        assertEquals("고래 공책", result.get(100L).getProductName());
        assertEquals(1, result.get(200L).getQuantity());
        assertEquals(3000L, result.get(200L).getAmount());
    }

    @Test
    void 여러_페이지를_같은_누적맵에_이어서_집계한다() throws Exception {
        Map<Long, OrderAggregate> acc = new LinkedHashMap<>();
        CoupangOrderSyncService.aggregatePage(json("""
                [{"orderItems": [{"vendorItemId": 100, "salesQuantity": 1, "unitSalesPrice": 1000}]}]
                """), acc);
        CoupangOrderSyncService.aggregatePage(json("""
                [{"orderItems": [{"vendorItemId": 100, "salesQuantity": 2, "unitSalesPrice": 1000}]}]
                """), acc);

        assertEquals(3, acc.get(100L).getQuantity());
        assertEquals(3000L, acc.get(100L).getAmount());
    }

    @Test
    void vendorItemId_없는_항목은_건너뛴다() throws Exception {
        JsonNode data = json("""
                [{"orderItems": [
                  {"vendorItemId": null, "salesQuantity": 1, "unitSalesPrice": 1000},
                  {"salesQuantity": 1, "unitSalesPrice": 1000}
                ]}]
                """);
        Map<Long, OrderAggregate> result = CoupangOrderSyncService.aggregatePage(data, new LinkedHashMap<>());
        assertTrue(result.isEmpty());
    }

    @Test
    void 상품명은_처음_발견된_값을_유지한다() throws Exception {
        JsonNode data = json("""
                [{"orderItems": [
                  {"vendorItemId": 100, "productName": null, "salesQuantity": 1, "unitSalesPrice": 1000},
                  {"vendorItemId": 100, "productName": "이름", "salesQuantity": 1, "unitSalesPrice": 1000},
                  {"vendorItemId": 200, "salesQuantity": 1, "unitSalesPrice": 500}
                ]}]
                """);
        Map<Long, OrderAggregate> result = CoupangOrderSyncService.aggregatePage(data, new LinkedHashMap<>());
        assertEquals("이름", result.get(100L).getProductName()); // null 이후 발견된 이름으로 채움
        assertNull(result.get(200L).getProductName());
        assertEquals(2, result.get(100L).getQuantity());
    }
}
