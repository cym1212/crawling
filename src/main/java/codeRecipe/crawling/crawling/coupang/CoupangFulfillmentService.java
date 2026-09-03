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

    public record BulkShipItem(Long orderId, Long shipmentBoxId, String deliveryCompanyCode, String invoiceNumber) {
    }

    public record BulkShipEntryResult(Long orderId, Long shipmentBoxId, boolean success, String message) {
    }

    public record BulkShipResult(int requested, int succeeded, int failed, List<BulkShipEntryResult> results) {
    }

    /**
     * 일괄 출고: 박스별로 ship()을 순차 실행한다 (호출 스로틀은 API 클라이언트가 보장).
     * 개별 건 실패는 건너뛰고 계속 진행하며 박스별 성공/실패를 반환한다 (부분 성공 허용).
     */
    public BulkShipResult shipBulk(List<BulkShipItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items(출고 목록)가 비어 있습니다");
        }
        List<BulkShipEntryResult> results = new ArrayList<>();
        int succeeded = 0;
        for (BulkShipItem item : items) {
            try {
                if (item.orderId() == null) {
                    throw new IllegalArgumentException("orderId가 없습니다");
                }
                ShipResult result = ship(item.orderId(), item.shipmentBoxId(),
                        item.deliveryCompanyCode(), item.invoiceNumber());
                results.add(new BulkShipEntryResult(item.orderId(), item.shipmentBoxId(), true, result.message()));
                succeeded++;
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                results.add(new BulkShipEntryResult(item.orderId(), item.shipmentBoxId(), false, message));
                log.warn("판매자배송 일괄 출고 개별 실패 orderId={} boxId={}: {}",
                        item.orderId(), item.shipmentBoxId(), message);
            }
        }
        log.info("판매자배송 일괄 출고 완료 요청={} 성공={} 실패={}", items.size(), succeeded, items.size() - succeeded);
        return new BulkShipResult(items.size(), succeeded, items.size() - succeeded, results);
    }

    public record AcknowledgeResult(int requested, int acknowledged, int alreadyAcknowledged,
                                    List<Long> notFound, List<Long> skippedCancelled, List<Long> skippedShipped,
                                    List<Long> receiverUpdated) {
    }

    /**
     * 발주확인 처리 (결제완료 → 상품준비중, Wing의 [발주확인 처리]와 동일).
     * 30-we 화면에서 체크한 배송박스들을 일괄 전환한다. 전환 후에는 구매자가 단독으로 취소할 수 없다.
     * 처리 직후 각 주문의 수취인 정보를 쿠팡에 재조회해 갱신한다 — 결제완료 동안 구매자가
     * 배송지를 바꿨을 수 있어서다 (쿠팡 공식 권장 절차). 이미 처리된 박스는 건너뛴다(멱등).
     */
    public AcknowledgeResult acknowledge(List<Long> shipmentBoxIds) {
        if (shipmentBoxIds == null || shipmentBoxIds.isEmpty()) {
            throw new IllegalArgumentException("shipmentBoxIds(배송박스 목록)가 비어 있습니다");
        }
        List<Long> distinct = shipmentBoxIds.stream().distinct().toList();
        var rowsByBox = orderRepository.findByShipmentBoxIdIn(distinct).stream()
                .collect(java.util.stream.Collectors.toMap(CoupangMarketplaceOrder::getShipmentBoxId, r -> r));

        List<Long> notFound = new ArrayList<>();
        List<Long> skippedCancelled = new ArrayList<>();
        List<Long> skippedShipped = new ArrayList<>();
        List<Long> alreadyAcked = new ArrayList<>();
        List<CoupangMarketplaceOrder> toAck = new ArrayList<>();
        for (Long boxId : distinct) {
            CoupangMarketplaceOrder row = rowsByBox.get(boxId);
            if (row == null) {
                notFound.add(boxId);
            } else if (CoupangMarketplaceOrder.STATUS_CANCELED.equals(row.getCoupangStatus())) {
                skippedCancelled.add(boxId);
            } else if (row.getShippedAt() != null) {
                skippedShipped.add(boxId);
            } else if (row.getAcknowledgedAt() != null) {
                alreadyAcked.add(boxId);
            } else {
                toAck.add(row);
            }
        }

        List<CoupangMarketplaceOrder> acked = new ArrayList<>();
        if (!toAck.isEmpty()) {
            JSONArray boxArray = new JSONArray();
            toAck.forEach(r -> boxArray.put(r.getShipmentBoxId()));
            String body = new JSONObject()
                    .put("vendorId", apiProperties.getVendorId())
                    .put("shipmentBoxIds", boxArray)
                    .toString();
            JsonNode response = coupangApiClient.patchOrdersheetAcknowledgement(body);
            assertCoupangSuccess(response, "발주확인 처리");
            LocalDateTime now = LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS);
            for (CoupangMarketplaceOrder row : toAck) {
                acked.add(row.toBuilder().acknowledgedAt(now).coupangStatus("INSTRUCT").build());
            }
            orderRepository.saveAll(acked);
            log.info("판매자배송 발주확인 완료 - {}건 (박스 {})", acked.size(), boxArray);
        }
        List<Long> receiverUpdated = refreshReceivers(acked);
        return new AcknowledgeResult(distinct.size(), acked.size(), alreadyAcked.size(),
                notFound, skippedCancelled, skippedShipped, receiverUpdated);
    }

    /** 발주확인 직후 수취인 정보 재조회·갱신. 실패해도 발주확인 자체는 유효하므로 로그만 남긴다. */
    private List<Long> refreshReceivers(List<CoupangMarketplaceOrder> rows) {
        List<Long> changed = new ArrayList<>();
        var byOrder = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(CoupangMarketplaceOrder::getOrderId));
        for (var entry : byOrder.entrySet()) {
            try {
                JsonNode data = coupangApiClient.getOrderSheetByOrderId(entry.getKey()).path("data");
                for (CoupangMarketplaceOrder row : entry.getValue()) {
                    JsonNode sheet = CoupangMarketplaceOrderService.pickShipmentBox(data, row.getShipmentBoxId());
                    if (sheet == null) {
                        continue;
                    }
                    JsonNode receiver = sheet.path("receiver");
                    String name = CoupangJsonUtils.textOrNull(receiver, "name");
                    String phone = CoupangJsonUtils.textOrNull(receiver, "safeNumber");
                    String addr1 = CoupangJsonUtils.textOrNull(receiver, "addr1");
                    String addr2 = CoupangJsonUtils.textOrNull(receiver, "addr2");
                    String postCode = CoupangJsonUtils.textOrNull(receiver, "postCode");
                    String message = CoupangJsonUtils.textOrNull(sheet, "parcelPrintMessage");
                    boolean same = java.util.Objects.equals(name, row.getReceiverName())
                            && java.util.Objects.equals(phone, row.getReceiverPhone())
                            && java.util.Objects.equals(addr1, row.getReceiverAddr1())
                            && java.util.Objects.equals(addr2, row.getReceiverAddr2())
                            && java.util.Objects.equals(postCode, row.getReceiverPostCode())
                            && java.util.Objects.equals(message, row.getParcelPrintMessage());
                    if (!same) {
                        orderRepository.save(row.toBuilder()
                                .receiverName(name).receiverPhone(phone)
                                .receiverAddr1(addr1).receiverAddr2(addr2).receiverPostCode(postCode)
                                .parcelPrintMessage(message)
                                .build());
                        changed.add(row.getShipmentBoxId());
                        log.info("판매자배송 박스 {} 수취인 정보 변경 감지 - 갱신", row.getShipmentBoxId());
                    }
                }
            } catch (Exception e) {
                log.warn("판매자배송 주문 {} 수취인 재조회 실패 - 기존 정보 유지", entry.getKey(), e);
            }
        }
        return changed;
    }

    /**
     * @param shipmentBoxId 출고할 배송박스. 주문의 박스가 하나뿐이면 생략 가능,
     *                      여러 개(배송비 그룹 분할)면 필수 — 준비중·송장 등록이 전부 박스 단위라서다.
     */
    public ShipResult ship(long orderId, Long shipmentBoxId, String deliveryCompanyCode, String invoiceNumber) {
        if (deliveryCompanyCode == null || deliveryCompanyCode.isBlank()) {
            throw new IllegalArgumentException("deliveryCompanyCode(택배사 코드)가 비어 있습니다");
        }
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            throw new IllegalArgumentException("invoiceNumber(운송장번호)가 비어 있습니다");
        }
        List<CoupangMarketplaceOrder> rows = orderRepository.findAllByOrderId(orderId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderId);
        }
        CoupangMarketplaceOrder order;
        if (shipmentBoxId != null) {
            order = rows.stream().filter(r -> shipmentBoxId.equals(r.getShipmentBoxId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "주문에 해당 배송박스가 없습니다: " + orderId + " / " + shipmentBoxId));
        } else if (rows.size() == 1) {
            order = rows.get(0);
        } else {
            throw new IllegalArgumentException(
                    "배송박스가 여러 개로 나뉜 주문입니다. shipmentBoxId를 지정해주세요: " + orderId);
        }
        if (CoupangMarketplaceOrder.STATUS_CANCELED.equals(order.getCoupangStatus())) {
            throw new IllegalStateException("취소/반품된 주문이라 출고할 수 없습니다: " + orderId);
        }
        if (order.getShippedAt() != null) {
            throw new IllegalStateException("이미 송장이 등록된 배송박스입니다 (송장번호 " + order.getTrackingNumber() + ")");
        }
        if (order.getShipmentBoxId() == null) {
            throw new IllegalStateException("배송박스 ID가 없어 처리할 수 없는 주문입니다: " + orderId);
        }
        // 이 박스에 속한 상품만 송장 등록 대상 (스키마 확장 전 수집분은 박스 null 폴백)
        Long boxId = order.getShipmentBoxId();
        List<CoupangMarketplaceOrderItem> allItems = orderItemRepository.findByOrderId(orderId);
        List<CoupangMarketplaceOrderItem> items = allItems.stream()
                .filter(i -> boxId.equals(i.getShipmentBoxId())).toList();
        if (items.isEmpty()) {
            items = allItems.stream().filter(i -> i.getShipmentBoxId() == null).toList();
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("배송박스의 상품 정보가 없습니다: " + orderId);
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
