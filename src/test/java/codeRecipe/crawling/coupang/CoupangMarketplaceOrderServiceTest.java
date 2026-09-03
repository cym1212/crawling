package codeRecipe.crawling.coupang;

import codeRecipe.crawling.crawling.coupang.CoupangMarketplaceOrderService;
import codeRecipe.crawling.crawling.coupang.CoupangMarketplaceOrderService.ParsedOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoupangMarketplaceOrderServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode json(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    @Test
    void 발주서에서_주문_수취인_상품을_전부_추출한다() throws Exception {
        JsonNode data = json("""
                [
                  {"orderId": 28000012345678, "shipmentBoxId": 123456789012345678,
                   "orderedAt": "2026-08-24T14:30:00+09:00", "paidAt": "2026-08-24T14:32:05+09:00",
                   "status": "ACCEPT",
                   "orderer": {"name": "신*희", "email": "", "safeNumber": "0502-1234-5678"},
                   "receiver": {"name": "신*희", "safeNumber": "0502-1234-5678",
                                "addr1": "경기도 성남시 수정구 창업로 43", "addr2": "1층", "postCode": "13449"},
                   "parcelPrintMessage": "문 앞에 놔주세요",
                   "orderItems": [
                     {"vendorItemId": 95296550974, "vendorItemName": "고래 공책, 1개 07번 (혹등고래)",
                      "shippingCount": 2, "salesPrice": 4900, "orderPrice": 9800},
                     {"vendorItemId": 95296317550, "vendorItemName": "스마일 머리끈, 더스티 블루",
                      "shippingCount": 1, "salesPrice": 4900, "orderPrice": 4900}
                   ]}
                ]
                """);
        List<ParsedOrder> orders = CoupangMarketplaceOrderService.parseOrders(data);

        assertEquals(1, orders.size());
        ParsedOrder order = orders.get(0);
        assertEquals(28000012345678L, order.orderId());
        assertEquals(123456789012345678L, order.shipmentBoxId());
        assertEquals(LocalDateTime.of(2026, 8, 24, 14, 32, 5), order.paidAt());
        assertEquals("ACCEPT", order.status());
        assertEquals("신*희", order.receiverName());
        assertEquals("0502-1234-5678", order.receiverPhone());
        assertEquals("경기도 성남시 수정구 창업로 43", order.receiverAddr1());
        assertEquals("13449", order.receiverPostCode());
        assertEquals("문 앞에 놔주세요", order.parcelPrintMessage());
        assertEquals(2, order.items().size());
        assertEquals(95296550974L, order.items().get(0).vendorItemId());
        assertEquals(2, order.items().get(0).quantity());
        assertEquals(3, order.totalQuantity());        // 2 + 1
        assertEquals(14700L, order.totalPrice());      // 9800 + 4900 (orderPrice 합)
    }

    @Test
    void 주문번호_없는_행은_건너뛴다() throws Exception {
        JsonNode data = json("""
                [
                  {"orderer": {"name": "무번호"}, "orderItems": []},
                  {"orderId": 0, "orderItems": []}
                ]
                """);
        assertTrue(CoupangMarketplaceOrderService.parseOrders(data).isEmpty());
    }

    @Test
    void 필드가_없어도_안전하게_파싱하고_orderPrice_없으면_단가x수량으로_계산한다() throws Exception {
        JsonNode data = json("""
                [{"orderId": 123, "orderItems": [{"vendorItemId": 1, "shippingCount": 3, "salesPrice": 1000}]}]
                """);
        List<ParsedOrder> orders = CoupangMarketplaceOrderService.parseOrders(data);

        assertEquals(1, orders.size());
        ParsedOrder order = orders.get(0);
        assertNull(order.shipmentBoxId());
        assertNull(order.receiverName());
        assertNull(order.paidAt());
        assertEquals(3, order.totalQuantity());
        assertEquals(3000L, order.totalPrice()); // orderPrice 없음 → 1000 × 3
    }

    @Test
    void 결제일시는_다양한_형식을_파싱한다() {
        assertEquals(LocalDateTime.of(2026, 8, 24, 14, 32, 5),
                CoupangMarketplaceOrderService.parseDateTime("2026-08-24T14:32:05+09:00"));
        assertEquals(LocalDateTime.of(2026, 8, 24, 14, 32, 5),
                CoupangMarketplaceOrderService.parseDateTime("2026-08-24T14:32:05"));
        assertNull(CoupangMarketplaceOrderService.parseDateTime(null));
        assertNull(CoupangMarketplaceOrderService.parseDateTime("이상한값"));
    }

    @Test
    void 단건조회에서_추적중인_배송박스를_고른다() throws Exception {
        JsonNode data = json("""
                [{"shipmentBoxId": 111, "status": "ACCEPT"},
                 {"shipmentBoxId": 222, "status": "DEPARTURE"}]
                """);
        assertEquals("DEPARTURE",
                CoupangMarketplaceOrderService.pickShipmentBox(data, 222L).path("status").asText());
        // 저장된 박스ID가 응답에 없으면(분리배송 재편 등) 첫 건으로 폴백
        assertEquals("ACCEPT",
                CoupangMarketplaceOrderService.pickShipmentBox(data, 999L).path("status").asText());
        assertEquals("ACCEPT",
                CoupangMarketplaceOrderService.pickShipmentBox(data, null).path("status").asText());
        assertNull(CoupangMarketplaceOrderService.pickShipmentBox(json("[]"), 111L));
        assertNull(CoupangMarketplaceOrderService.pickShipmentBox(json("{}"), 111L));
    }

    @Test
    void 송장번호는_박스레벨_우선_상품레벨_폴백으로_추출한다() throws Exception {
        assertEquals("612345",
                CoupangMarketplaceOrderService.extractInvoiceNumber(json("""
                        {"invoiceNumber": "612345", "orderItems": [{"invoiceNumber": "다른값"}]}
                        """)));
        assertEquals("7999",
                CoupangMarketplaceOrderService.extractInvoiceNumber(json("""
                        {"invoiceNumber": null, "orderItems": [{"vendorItemId": 1}, {"invoiceNumber": "7999"}]}
                        """)));
        assertNull(CoupangMarketplaceOrderService.extractInvoiceNumber(json("""
                        {"orderItems": [{"vendorItemId": 1}]}
                        """)));
    }
}
