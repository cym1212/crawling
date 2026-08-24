package codeRecipe.crawling.coupang;

import codeRecipe.crawling.crawling.coupang.CoupangMarketplaceOrderAlertService;
import codeRecipe.crawling.crawling.coupang.CoupangMarketplaceOrderAlertService.NewOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoupangMarketplaceOrderAlertServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode json(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    @Test
    void 발주서에서_주문번호_주문자_상품_수량을_추출한다() throws Exception {
        JsonNode data = json("""
                [
                  {"orderId": 28000012345678, "orderer": {"name": "홍길동"}, "paidAt": "2026-08-24T14:32:05+09:00",
                   "orderItems": [
                     {"vendorItemName": "귀여운 파란 고래 우드 키링, FREE 블루", "shippingCount": 2},
                     {"vendorItemName": "고래 공책, 1개 07번", "shippingCount": 1}
                   ]}
                ]
                """);
        List<NewOrder> orders = CoupangMarketplaceOrderAlertService.parseOrders(data);

        assertEquals(1, orders.size());
        NewOrder order = orders.get(0);
        assertEquals(28000012345678L, order.orderId());
        assertEquals("홍길동", order.ordererName());
        assertEquals(LocalDateTime.of(2026, 8, 24, 14, 32, 5), order.paidAt());
        assertEquals(2, order.itemLines().size());
        assertEquals("귀여운 파란 고래 우드 키링, FREE 블루 — 2개", order.itemLines().get(0));
    }

    @Test
    void 주문번호_없는_행은_건너뛴다() throws Exception {
        JsonNode data = json("""
                [
                  {"orderer": {"name": "무번호"}, "orderItems": []},
                  {"orderId": 0, "orderItems": []}
                ]
                """);
        assertTrue(CoupangMarketplaceOrderAlertService.parseOrders(data).isEmpty());
    }

    @Test
    void 필드가_없어도_안전하게_파싱한다() throws Exception {
        JsonNode data = json("""
                [{"orderId": 123, "orderItems": [{"shippingCount": 1}]}]
                """);
        List<NewOrder> orders = CoupangMarketplaceOrderAlertService.parseOrders(data);

        assertEquals(1, orders.size());
        assertNull(orders.get(0).ordererName());
        assertNull(orders.get(0).paidAt());
        assertEquals("(상품명 없음) — 1개", orders.get(0).itemLines().get(0));
    }
}
