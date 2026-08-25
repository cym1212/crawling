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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 판매자배송(마켓플레이스) 주문 수집 + 다이제스트 슬랙 알림.
 * - 수집: 최근 23시간 결제완료(ACCEPT) 발주서를 조회해 신규 주문만 저장 (주문+상품+수취인)
 * - 다이제스트: 하루 2회(기본 09:00/12:00) 미알림 주문을 확정 템플릿의 Block Kit 카드로 발송
 * - 0건이면 발송하지 않음
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangMarketplaceOrderService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME_PARAM = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter DIGEST_HEADER_TIME = DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", Locale.KOREAN);
    private static final DateTimeFormatter PAID_DISPLAY = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int MAX_PAGES = 50;
    private static final int MAX_CARD_ORDERS = 15; // Block Kit 50블록 제한 대비. 초과분은 다음 발송에 포함

    private final CoupangApiClient coupangApiClient;
    private final CoupangMarketplaceOrderRepository orderRepository;
    private final CoupangMarketplaceOrderItemRepository orderItemRepository;
    private final CoupangSlackNotifier slackNotifier;

    /** 다이제스트 푸터의 출고 화면 링크 (비우면 텍스트로만 안내) */
    @Value("${coupang.slack.order-screen-url:}")
    private String orderScreenUrl;

    // ── 파싱 (순수 함수 — 단위 테스트 대상) ──────────────────────────────

    public record ParsedItem(Long vendorItemId, String productName, int quantity, long unitPrice, long orderPrice) {
    }

    public record ParsedOrder(long orderId, Long shipmentBoxId, LocalDateTime orderedAt, LocalDateTime paidAt,
                              String status, String ordererName, String receiverName, String receiverPhone,
                              String receiverAddr1, String receiverAddr2, String receiverPostCode,
                              String parcelPrintMessage, List<ParsedItem> items) {

        public int totalQuantity() {
            return items.stream().mapToInt(ParsedItem::quantity).sum();
        }

        public long totalPrice() {
            return items.stream().mapToLong(i -> i.orderPrice() > 0 ? i.orderPrice()
                    : i.unitPrice() * i.quantity()).sum();
        }
    }

    /** 발주서 응답 data 배열(배송박스 단위)을 주문 정보로 파싱한다 */
    public static List<ParsedOrder> parseOrders(JsonNode dataArray) {
        List<ParsedOrder> orders = new ArrayList<>();
        for (JsonNode orderNode : dataArray) {
            long orderId = orderNode.path("orderId").asLong(0);
            if (orderId <= 0) {
                continue;
            }
            JsonNode boxNode = orderNode.path("shipmentBoxId");
            Long shipmentBoxId = boxNode.isMissingNode() || boxNode.isNull() ? null : boxNode.asLong();

            JsonNode orderer = orderNode.path("orderer");
            JsonNode receiver = orderNode.path("receiver");

            List<ParsedItem> items = new ArrayList<>();
            for (JsonNode itemNode : orderNode.path("orderItems")) {
                JsonNode vidNode = itemNode.path("vendorItemId");
                items.add(new ParsedItem(
                        vidNode.isMissingNode() || vidNode.isNull() ? null : vidNode.asLong(),
                        CoupangJsonUtils.textOrNull(itemNode, "vendorItemName"),
                        itemNode.path("shippingCount").asInt(0),
                        Math.round(itemNode.path("salesPrice").asDouble(0)),
                        Math.round(itemNode.path("orderPrice").asDouble(0))));
            }

            orders.add(new ParsedOrder(
                    orderId,
                    shipmentBoxId,
                    parseDateTime(CoupangJsonUtils.textOrNull(orderNode, "orderedAt")),
                    parseDateTime(CoupangJsonUtils.textOrNull(orderNode, "paidAt")),
                    CoupangJsonUtils.textOrNull(orderNode, "status"),
                    CoupangJsonUtils.textOrNull(orderer, "name"),
                    CoupangJsonUtils.textOrNull(receiver, "name"),
                    CoupangJsonUtils.textOrNull(receiver, "safeNumber"),
                    CoupangJsonUtils.textOrNull(receiver, "addr1"),
                    CoupangJsonUtils.textOrNull(receiver, "addr2"),
                    CoupangJsonUtils.textOrNull(receiver, "postCode"),
                    CoupangJsonUtils.textOrNull(orderNode, "parcelPrintMessage"),
                    items));
        }
        return orders;
    }

    /** ISO-8601(오프셋 포함) 또는 로컬 형식, epoch millis 문자열까지 방어적으로 파싱 */
    public static LocalDateTime parseDateTime(String text) {
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(SEOUL).toLocalDateTime();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(text.length() > 19 ? text.substring(0, 19) : text);
        } catch (Exception ignored) {
            // fall through
        }
        try {
            long epochMillis = Long.parseLong(text);
            return java.time.Instant.ofEpochMilli(epochMillis).atZone(SEOUL).toLocalDateTime();
        } catch (Exception ignored) {
            return null;
        }
    }

    // ── 수집 ─────────────────────────────────────────────────────────

    /**
     * 최근 23시간의 결제완료(ACCEPT) 주문 중 신규만 저장. @return 신규 저장 건수
     * 창을 23시간으로 잡는 이유: 쿠팡 발주서 API는 조회 창이 24시간 "미만"이어야 해서
     * 정확히 24시간이면 400(range should less than 0 day)이 난다. 다이제스트 간 최대 간격은
     * 21시간(12:00→익일 09:00)이라 23시간이면 누락 없이 안전하다.
     */
    public int collectNewOrders() {
        LocalDateTime now = LocalDateTime.now(SEOUL);
        String from = now.minusHours(23).format(TIME_PARAM) + "%2B09:00";
        String to = now.format(TIME_PARAM) + "%2B09:00";

        List<ParsedOrder> parsed = new ArrayList<>();
        String nextToken = null;
        int pages = 0;
        Set<String> seenTokens = new HashSet<>();
        do {
            JsonNode response = coupangApiClient.getMarketplaceOrderSheets(from, to, "ACCEPT");
            JsonNode data = response.path("data");
            if (!data.isArray()) {
                throw new CoupangApiException(
                        "쿠팡 발주서 응답 형식 오류: " + response.path("message").asText(""), null, null);
            }
            parsed.addAll(parseOrders(data));
            nextToken = CoupangJsonUtils.textOrNull(response, "nextToken");
            if (nextToken != null && !seenTokens.add(nextToken)) {
                log.warn("쿠팡 발주서 nextToken 반복 감지 - 페이징 중단");
                break;
            }
            pages++;
        } while (nextToken != null && pages < MAX_PAGES);

        LocalDateTime collectedAt = now.truncatedTo(ChronoUnit.SECONDS);
        Set<Long> seenOrderIds = new HashSet<>();
        int saved = 0;
        for (ParsedOrder order : parsed) {
            if (!seenOrderIds.add(order.orderId())) {
                // 분리배송(같은 주문 다중 박스): 첫 박스만 저장 (희귀 케이스)
                log.warn("판매자배송 주문 {} 에 배송박스가 여러 개 — 첫 박스만 저장", order.orderId());
                continue;
            }
            if (orderRepository.existsByOrderId(order.orderId())) {
                continue;
            }
            orderRepository.save(CoupangMarketplaceOrder.builder()
                    .orderId(order.orderId())
                    .shipmentBoxId(order.shipmentBoxId())
                    .orderedAt(order.orderedAt())
                    .paidAt(order.paidAt())
                    .coupangStatus(order.status() != null ? order.status() : "ACCEPT")
                    .ordererName(order.ordererName())
                    .receiverName(order.receiverName())
                    .receiverPhone(order.receiverPhone())
                    .receiverAddr1(order.receiverAddr1())
                    .receiverAddr2(order.receiverAddr2())
                    .receiverPostCode(order.receiverPostCode())
                    .parcelPrintMessage(order.parcelPrintMessage())
                    .totalQuantity(order.totalQuantity())
                    .totalPrice(order.totalPrice())
                    .collectedAt(collectedAt)
                    .build());
            List<CoupangMarketplaceOrderItem> itemEntities = new ArrayList<>();
            for (ParsedItem item : order.items()) {
                itemEntities.add(CoupangMarketplaceOrderItem.builder()
                        .orderId(order.orderId())
                        .vendorItemId(item.vendorItemId())
                        .productName(item.productName())
                        .quantity(item.quantity())
                        .unitPrice(item.unitPrice())
                        .build());
            }
            orderItemRepository.saveAll(itemEntities);
            saved++;
        }
        log.info("판매자배송 주문 수집 완료 조회={} 신규저장={}", parsed.size(), saved);
        return saved;
    }

    // ── 다이제스트 ────────────────────────────────────────────────────

    public record DigestResult(int collectedCount, int unnotifiedCount, int notifiedCount,
                               boolean slackSent, String preview) {
    }

    /** 스케줄 진입점: 수집 후 다이제스트 발송 */
    public DigestResult runDigestJob() {
        int collected = collectNewOrders();
        DigestResult result = sendDigest(false, collected);
        log.info("판매자배송 다이제스트 완료 수집={} 발송대상={} 발송={}",
                collected, result.unnotifiedCount(), result.slackSent());
        return result;
    }

    /**
     * 미알림 주문 다이제스트 발송.
     * @param dryRun true면 발송·알림처리 없이 미리보기만 반환
     */
    public DigestResult sendDigest(boolean dryRun, int collectedCount) {
        List<CoupangMarketplaceOrder> unnotified = orderRepository.findByNotifiedAtIsNullOrderByPaidAtAsc();
        if (unnotified.isEmpty()) {
            log.info("판매자배송 다이제스트 - 신규 주문 없음 (발송 생략)");
            return new DigestResult(collectedCount, 0, 0, false, "신규 주문 없음");
        }

        List<Long> orderIds = unnotified.stream().map(CoupangMarketplaceOrder::getOrderId).toList();
        Map<Long, List<CoupangMarketplaceOrderItem>> itemsByOrderId = orderItemRepository.findByOrderIdIn(orderIds)
                .stream().collect(Collectors.groupingBy(CoupangMarketplaceOrderItem::getOrderId));

        List<CoupangMarketplaceOrder> shown = unnotified.size() > MAX_CARD_ORDERS
                ? unnotified.subList(0, MAX_CARD_ORDERS) : unnotified;
        int remaining = unnotified.size() - shown.size();

        LocalDateTime now = LocalDateTime.now(SEOUL);
        String fallback = buildFallbackText(shown, itemsByOrderId, remaining);
        if (dryRun) {
            return new DigestResult(collectedCount, unnotified.size(), 0, false, fallback);
        }

        JSONArray blocks = buildBlocks(shown, itemsByOrderId, remaining, now);
        boolean sent = slackNotifier.sendOrderCard(fallback, blocks);

        LocalDateTime notifiedAt = now.truncatedTo(ChronoUnit.SECONDS);
        List<CoupangMarketplaceOrder> toMark = new ArrayList<>();
        for (CoupangMarketplaceOrder order : shown) {
            toMark.add(order.toBuilder().notifiedAt(notifiedAt).build());
        }
        orderRepository.saveAll(toMark);
        return new DigestResult(collectedCount, unnotified.size(), toMark.size(), sent, fallback);
    }

    private JSONArray buildBlocks(List<CoupangMarketplaceOrder> orders,
                                  Map<Long, List<CoupangMarketplaceOrderItem>> itemsByOrderId,
                                  int remaining, LocalDateTime now) {
        JSONArray blocks = new JSONArray();
        blocks.put(new JSONObject().put("type", "header").put("text", new JSONObject()
                .put("type", "plain_text")
                .put("text", "🛒 판매자배송 신규 주문 " + orders.size() + "건")
                .put("emoji", true)));
        blocks.put(contextBlock(now.format(DIGEST_HEADER_TIME) + " 발송"));
        blocks.put(divider());

        for (CoupangMarketplaceOrder order : orders) {
            List<CoupangMarketplaceOrderItem> items = itemsByOrderId.getOrDefault(order.getOrderId(), List.of());
            StringBuilder title = new StringBuilder();
            boolean first = true;
            for (CoupangMarketplaceOrderItem item : items) {
                if (!first) {
                    title.append("\n+ ");
                }
                title.append("*").append(escapeMrkdwn(item.getProductName() != null ? item.getProductName() : "(상품명 없음)"))
                        .append("* — `").append(item.getQuantity()).append("개`");
                first = false;
            }
            if (items.isEmpty()) {
                title.append("*(상품 정보 없음)* — `").append(order.getTotalQuantity()).append("개`");
            }
            blocks.put(new JSONObject().put("type", "section")
                    .put("text", new JSONObject().put("type", "mrkdwn").put("text", title.toString())));
            String paidText = order.getPaidAt() != null ? order.getPaidAt().format(PAID_DISPLAY) : "-";
            blocks.put(contextBlock("결제 " + paidText + " · 주문번호 " + order.getOrderId()));
            blocks.put(divider());
        }

        if (remaining > 0) {
            blocks.put(contextBlock("외 " + remaining + "건 — 다음 발송에 포함됩니다"));
        }
        String footer = orderScreenUrl != null && !orderScreenUrl.isBlank()
                ? "출고 처리 → <" + orderScreenUrl + "|쿠팡 주문 화면>"
                : "출고 처리 → 쿠팡 주문 화면 (jack.coderecipe.io)";
        blocks.put(contextBlock(footer));
        return blocks;
    }

    private String buildFallbackText(List<CoupangMarketplaceOrder> orders,
                                     Map<Long, List<CoupangMarketplaceOrderItem>> itemsByOrderId, int remaining) {
        StringBuilder text = new StringBuilder("🛒 판매자배송 신규 주문 " + orders.size() + "건");
        for (CoupangMarketplaceOrder order : orders) {
            List<CoupangMarketplaceOrderItem> items = itemsByOrderId.getOrDefault(order.getOrderId(), List.of());
            String itemSummary = items.isEmpty()
                    ? order.getTotalQuantity() + "개"
                    : items.stream().map(i -> (i.getProductName() != null ? i.getProductName() : "(상품명 없음)")
                            + " " + i.getQuantity() + "개").collect(Collectors.joining(" + "));
            text.append("\n· ").append(itemSummary).append(" (주문번호 ").append(order.getOrderId()).append(")");
        }
        if (remaining > 0) {
            text.append("\n외 ").append(remaining).append("건");
        }
        return text.toString();
    }

    private static JSONObject contextBlock(String text) {
        return new JSONObject().put("type", "context").put("elements",
                new JSONArray().put(new JSONObject().put("type", "mrkdwn").put("text", text)));
    }

    private static JSONObject divider() {
        return new JSONObject().put("type", "divider");
    }

    private static String escapeMrkdwn(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
