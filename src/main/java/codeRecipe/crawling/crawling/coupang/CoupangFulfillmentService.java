package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.domain.CoupangMarketplaceOrder;
import codeRecipe.crawling.crawling.domain.CoupangMarketplaceOrderItem;
import codeRecipe.crawling.crawling.repository.CoupangMarketplaceOrderItemRepository;
import codeRecipe.crawling.crawling.repository.CoupangMarketplaceOrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 판매자배송 출고 처리 (30-we.com [쿠팡 등록] 버튼이 내부 API로 호출).
 * 순서: 상품준비중 처리(미처리 시) → 송장 등록. 송장 등록되면 쿠팡이 배송지시로 전환하고 이후 추적은 자동.
 * 준비중까지 성공하고 송장 등록이 실패한 경우, 재호출하면 준비중은 건너뛰고 송장부터 재시도한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangFulfillmentService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CoupangApiClient coupangApiClient;
    private final CoupangApiProperties apiProperties;
    private final CoupangMarketplaceOrderRepository orderRepository;
    private final CoupangMarketplaceOrderItemRepository orderItemRepository;

    public record ShipResult(long orderId, boolean acknowledged, boolean invoiceRegistered,
                             String deliveryCompanyCode, String trackingNumber, String message) {
    }

    public ShipResult ship(long orderId, String deliveryCompanyCode, String invoiceNumber) {
        if (deliveryCompanyCode == null || deliveryCompanyCode.isBlank()) {
            throw new IllegalArgumentException("deliveryCompanyCode(택배사 코드)가 비어 있습니다");
        }
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            throw new IllegalArgumentException("invoiceNumber(운송장번호)가 비어 있습니다");
        }
        CoupangMarketplaceOrder order = orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderId));
        if (order.getShippedAt() != null) {
            throw new IllegalStateException("이미 송장이 등록된 주문입니다 (송장번호 " + order.getTrackingNumber() + ")");
        }
        if (order.getShipmentBoxId() == null) {
            throw new IllegalStateException("배송박스 ID가 없어 처리할 수 없는 주문입니다: " + orderId);
        }
        List<CoupangMarketplaceOrderItem> items = orderItemRepository.findByOrderId(orderId);
        if (items.isEmpty()) {
            throw new IllegalStateException("주문 상품 정보가 없습니다: " + orderId);
        }
        for (CoupangMarketplaceOrderItem item : items) {
            if (item.getVendorItemId() == null) {
                throw new IllegalStateException("옵션ID가 없는 상품이 있어 송장 등록이 불가합니다: " + orderId);
            }
        }

        LocalDateTime now = LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS);
        boolean acknowledgedNow = false;

        // 1단계: 상품준비중 처리 (이미 처리됐으면 건너뜀 — 재시도 안전)
        if (order.getAcknowledgedAt() == null) {
            String ackBody = new JSONObject()
                    .put("vendorId", apiProperties.getVendorId())
                    .put("shipmentBoxIds", new JSONArray().put(order.getShipmentBoxId()))
                    .toString();
            JsonNode ackResponse = coupangApiClient.patchOrdersheetAcknowledgement(ackBody);
            assertCoupangSuccess(ackResponse, "상품준비중 처리");
            order = orderRepository.save(order.toBuilder()
                    .acknowledgedAt(now)
                    .coupangStatus("INSTRUCT")
                    .build());
            acknowledgedNow = true;
            log.info("판매자배송 준비중 처리 완료 orderId={}", orderId);
        }

        // 2단계: 송장 등록 (옵션ID별 DTO, 같은 송장번호)
        JSONArray dtos = new JSONArray();
        for (CoupangMarketplaceOrderItem item : items) {
            dtos.put(new JSONObject()
                    .put("shipmentBoxId", order.getShipmentBoxId())
                    .put("orderId", orderId)
                    .put("vendorItemId", item.getVendorItemId())
                    .put("deliveryCompanyCode", deliveryCompanyCode)
                    .put("invoiceNumber", invoiceNumber)
                    .put("splitShipping", false)
                    .put("preSplitShipped", false)
                    .put("estimatedShippingDate", ""));
        }
        String invoiceBody = new JSONObject()
                .put("vendorId", apiProperties.getVendorId())
                .put("orderSheetInvoiceApplyDtos", dtos)
                .toString();
        JsonNode invoiceResponse = coupangApiClient.postOrderInvoices(invoiceBody);
        assertCoupangSuccess(invoiceResponse, "송장 등록");

        orderRepository.save(order.toBuilder()
                .deliveryCompanyCode(deliveryCompanyCode)
                .trackingNumber(invoiceNumber)
                .shippedAt(now)
                .coupangStatus("DEPARTURE")
                .build());
        log.info("판매자배송 송장 등록 완료 orderId={} courier={} invoice={}", orderId, deliveryCompanyCode, invoiceNumber);

        return new ShipResult(orderId, acknowledgedNow, true, deliveryCompanyCode, invoiceNumber,
                "출고 처리 완료 (준비중 " + (acknowledgedNow ? "처리" : "기처리") + " + 송장 등록)");
    }

    /** 쿠팡 벌크 응답 검증: data.responseCode 0(전체 성공) 외에는 실패로 처리 */
    private void assertCoupangSuccess(JsonNode response, String action) {
        JsonNode data = response.path("data");
        int responseCode = data.path("responseCode").asInt(-1);
        if (responseCode == 0) {
            return;
        }
        List<String> details = new ArrayList<>();
        for (JsonNode item : data.path("responseList")) {
            if (!item.path("succeed").asBoolean(false)) {
                String code = item.path("resultCode").asText("");
                String message = item.path("resultMessage").asText("");
                details.add((code + " " + message).trim());
            }
        }
        String detail = details.isEmpty() ? data.path("responseMessage").asText("") : String.join("; ", details);
        throw new CoupangApiException(action + " 실패: " + detail, null, null);
    }
}
