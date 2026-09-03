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
 * 판매자배송(마켓플레이스) 주문 수집 + 상태 동기화 + 다이제스트 슬랙 알림.
 * 다이제스트(하루 2회, 기본 08:00/12:00) 실행 순서:
 * 1. 상태 동기화 — 미출고로 남은 주문을 쿠팡에 단건 재조회해서 Wing에서 처리됐거나
 *    취소/반품된 주문을 DB에 반영한다 (30-we 미출고 탭·재알림에서 자동 제외)
 * 2. 수집 — 최근 23시간 결제완료(ACCEPT) 발주서를 조회해 신규 주문만 저장 (주문+상품+수취인)
 * 3. 발송 — 신규 주문 + (동기화를 통과한) 미처리 주문 재알림 + 취소 감지를 한 장의 카드로 발송.
 *    전부 0건이면 발송하지 않음
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
    // Block Kit 50블록 제한 대비 (신규+미처리 합산 표시 상한: 12건×3블록 + 헤더·취소·Wing·푸터 여유분)
    private static final int MAX_CARD_ORDERS = 12;
    private static final int MAX_CARD_CANCELLED = 5;

    private static final String STATUS_ACCEPT = "ACCEPT";
    private static final String STATUS_INSTRUCT = "INSTRUCT";

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

    // ── 상태 동기화 (Wing 직접 처리·취소 감지) ─────────────────────────

    public record StatusSyncResult(int checked, List<CoupangMarketplaceOrder> wingProcessed,
                                   List<CoupangMarketplaceOrder> cancelled) {
        public static StatusSyncResult empty() {
            return new StatusSyncResult(0, List.of(), List.of());
        }
    }

    /**
     * 미출고로 남은 주문들의 현재 상태를 쿠팡에 단건 재조회해서 DB를 실제 상태와 맞춘다.
     * - Wing에서 송장까지 등록됨(DEPARTURE 이후) → shippedAt 기록 (미출고 탭·재알림에서 제외)
     * - Wing에서 준비중(INSTRUCT)까지만 처리 → 상태만 갱신, 미출고 유지 (송장은 아직이므로)
     * - 취소/반품됨 → 단건 조회가 400을 반환하는 것으로 감지, CANCELED 기록
     * 개별 건 조회 실패는 로그만 남기고 다음 동기화에서 재시도한다 (전체 잡을 죽이지 않음).
     */
    public StatusSyncResult syncPendingOrderStatuses() {
        List<CoupangMarketplaceOrder> pending = orderRepository.findByShippedAtIsNullOrderByPaidAtAsc().stream()
                .filter(o -> !CoupangMarketplaceOrder.STATUS_CANCELED.equals(o.getCoupangStatus()))
                .toList();
        List<CoupangMarketplaceOrder> wingProcessed = new ArrayList<>();
        List<CoupangMarketplaceOrder> cancelled = new ArrayList<>();
        for (CoupangMarketplaceOrder order : pending) {
            try {
                JsonNode response = coupangApiClient.getOrderSheetByOrderId(order.getOrderId());
                JsonNode sheet = pickShipmentBox(response.path("data"), order.getShipmentBoxId());
                if (sheet == null) {
                    log.warn("판매자배송 주문 {} 단건 조회에 발주서 없음 - 유지", order.getOrderId());
                    continue;
                }
                String status = CoupangJsonUtils.textOrNull(sheet, "status");
                if (status == null || STATUS_ACCEPT.equals(status)) {
                    continue; // 여전히 결제완료 - 미출고 유지 (재알림 대상)
                }
                if (STATUS_INSTRUCT.equals(status)) {
                    // 준비중 이력(acknowledgedAt)도 함께 기록해야 이후 [쿠팡 등록]이 준비중 처리를
                    // 중복 요청하지 않고 송장 등록부터 이어간다 (Wing에서 준비중까지만 하고 넘어온 케이스)
                    if (!STATUS_INSTRUCT.equals(order.getCoupangStatus()) || order.getAcknowledgedAt() == null) {
                        orderRepository.save(order.toBuilder()
                                .coupangStatus(STATUS_INSTRUCT)
                                .acknowledgedAt(order.getAcknowledgedAt() != null ? order.getAcknowledgedAt()
                                        : LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS))
                                .build());
                        log.info("판매자배송 주문 {} Wing 준비중 처리 감지 - 미출고 유지(송장 등록만 남음)",
                                order.getOrderId());
                    }
                    continue;
                }
                // DEPARTURE/DELIVERING/FINAL_DELIVERY/NONE_TRACKING — 외부(Wing)에서 송장 등록 완료
                String invoice = extractInvoiceNumber(sheet);
                String courier = CoupangJsonUtils.textOrNull(sheet, "deliveryCompanyName");
                CoupangMarketplaceOrder updated = orderRepository.save(order.toBuilder()
                        .coupangStatus(status)
                        .shippedAt(LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS))
                        .trackingNumber(invoice != null ? invoice : order.getTrackingNumber())
                        .deliveryCompanyCode(courier != null ? courier : order.getDeliveryCompanyCode())
                        .build());
                wingProcessed.add(updated);
                log.info("판매자배송 주문 {} Wing 처리 감지 - 상태 {} 송장 {}", order.getOrderId(), status, invoice);
            } catch (CoupangApiException e) {
                if (e.getStatus() != null && e.getStatus().value() == 400) {
                    // 발주서 단건 조회는 취소/반품된 주문에 400을 반환한다 (공식 문서 명시)
                    CoupangMarketplaceOrder updated = orderRepository.save(order.toBuilder()
                            .coupangStatus(CoupangMarketplaceOrder.STATUS_CANCELED).build());
                    cancelled.add(updated);
                    log.info("판매자배송 주문 {} 취소/반품 감지 (단건 조회 400)", order.getOrderId());
                } else {
                    log.warn("판매자배송 주문 {} 상태 재조회 실패 - 다음 동기화에 재시도", order.getOrderId(), e);
                }
            }
        }
        if (!pending.isEmpty()) {
            log.info("판매자배송 상태 동기화 완료 대상={} Wing처리={} 취소={}",
                    pending.size(), wingProcessed.size(), cancelled.size());
        }
        return new StatusSyncResult(pending.size(), wingProcessed, cancelled);
    }

    /** 단건 조회 data 배열에서 우리가 추적 중인 배송박스를 고른다 (없으면 첫 건, 빈 배열이면 null) */
    public static JsonNode pickShipmentBox(JsonNode dataArray, Long shipmentBoxId) {
        if (!dataArray.isArray() || dataArray.isEmpty()) {
            return null;
        }
        if (shipmentBoxId != null) {
            for (JsonNode node : dataArray) {
                if (node.path("shipmentBoxId").asLong(-1) == shipmentBoxId) {
                    return node;
                }
            }
        }
        return dataArray.get(0);
    }

    /** 발주서에서 등록된 송장번호 추출 (박스 레벨 → 상품 레벨 순으로 방어적 탐색) */
    public static String extractInvoiceNumber(JsonNode sheet) {
        String boxLevel = CoupangJsonUtils.textOrNull(sheet, "invoiceNumber");
        if (boxLevel != null) {
            return boxLevel;
        }
        for (JsonNode item : sheet.path("orderItems")) {
            String itemLevel = CoupangJsonUtils.textOrNull(item, "invoiceNumber");
            if (itemLevel != null) {
                return itemLevel;
            }
        }
        return null;
    }

    // ── 다이제스트 ────────────────────────────────────────────────────

    public record DigestResult(int collectedCount, int newCount, int notifiedCount, int reminderCount,
                               int wingProcessedCount, int cancelledCount, boolean slackSent, String preview) {
    }

    /** 스케줄 진입점: 상태 동기화 → 수집 → 다이제스트 발송 */
    public DigestResult runDigestJob() {
        StatusSyncResult sync = syncPendingOrderStatuses();
        int collected = collectNewOrders();
        DigestResult result = sendDigest(false, collected, sync);
        log.info("판매자배송 다이제스트 완료 수집={} 신규={} 미처리={} Wing처리={} 취소={} 발송={}",
                collected, result.newCount(), result.reminderCount(),
                result.wingProcessedCount(), result.cancelledCount(), result.slackSent());
        return result;
    }

    /**
     * 다이제스트 발송: 신규 주문 + 미처리 재알림 + 취소 감지를 한 장의 카드로.
     * @param dryRun true면 슬랙 발송·알림 처리 없이 미리보기만 반환
     * @param sync   직전에 실행한 상태 동기화 결과 (취소 감지·Wing 처리 안내에 사용)
     */
    public DigestResult sendDigest(boolean dryRun, int collectedCount, StatusSyncResult sync) {
        List<CoupangMarketplaceOrder> unnotified = orderRepository.findByNotifiedAtIsNullOrderByPaidAtAsc();
        // 미처리 재알림: 이전에 알림했지만 여전히 미출고인 주문 (Wing 처리·취소는 동기화에서 이미 제외됨)
        List<CoupangMarketplaceOrder> reminders = orderRepository.findByShippedAtIsNullOrderByPaidAtAsc().stream()
                .filter(o -> o.getNotifiedAt() != null)
                .filter(o -> !CoupangMarketplaceOrder.STATUS_CANCELED.equals(o.getCoupangStatus()))
                .toList();
        List<CoupangMarketplaceOrder> cancelled = sync.cancelled();
        int wingProcessedCount = sync.wingProcessed().size();

        if (unnotified.isEmpty() && reminders.isEmpty() && cancelled.isEmpty()) {
            log.info("판매자배송 다이제스트 - 신규·미처리·취소 없음 (발송 생략)");
            return new DigestResult(collectedCount, 0, 0, 0, wingProcessedCount, 0, false, "알릴 내용 없음");
        }

        // 신규+미처리 합산 표시 상한 (신규 우선, 초과분은 다음 발송에 포함)
        List<CoupangMarketplaceOrder> shownNew = unnotified.size() > MAX_CARD_ORDERS
                ? unnotified.subList(0, MAX_CARD_ORDERS) : unnotified;
        int reminderBudget = Math.max(0, MAX_CARD_ORDERS - shownNew.size());
        List<CoupangMarketplaceOrder> shownReminders = reminders.size() > reminderBudget
                ? reminders.subList(0, reminderBudget) : reminders;
        int remaining = (unnotified.size() - shownNew.size()) + (reminders.size() - shownReminders.size());

        Set<Long> orderIds = new HashSet<>();
        shownNew.forEach(o -> orderIds.add(o.getOrderId()));
        shownReminders.forEach(o -> orderIds.add(o.getOrderId()));
        cancelled.forEach(o -> orderIds.add(o.getOrderId()));
        Map<Long, List<CoupangMarketplaceOrderItem>> itemsByOrderId =
                orderItemRepository.findByOrderIdIn(new ArrayList<>(orderIds))
                        .stream().collect(Collectors.groupingBy(CoupangMarketplaceOrderItem::getOrderId));

        LocalDateTime now = LocalDateTime.now(SEOUL);
        String fallback = buildFallbackText(shownNew, shownReminders, cancelled, itemsByOrderId, remaining);
        if (dryRun) {
            return new DigestResult(collectedCount, unnotified.size(), 0, reminders.size(),
                    wingProcessedCount, cancelled.size(), false, fallback);
        }

        JSONArray blocks = buildBlocks(shownNew, shownReminders, cancelled, wingProcessedCount,
                itemsByOrderId, remaining, now);
        boolean sent = slackNotifier.sendOrderCard(fallback, blocks);

        // 발송 성공 시에만 알림 완료 표시 (실패하면 다음 다이제스트에 신규로 재포함)
        int notified = 0;
        if (sent) {
            LocalDateTime notifiedAt = now.truncatedTo(ChronoUnit.SECONDS);
            List<CoupangMarketplaceOrder> toMark = new ArrayList<>();
            for (CoupangMarketplaceOrder order : shownNew) {
                toMark.add(order.toBuilder().notifiedAt(notifiedAt).build());
            }
            orderRepository.saveAll(toMark);
            notified = toMark.size();
        }
        return new DigestResult(collectedCount, unnotified.size(), notified, reminders.size(),
                wingProcessedCount, cancelled.size(), sent, fallback);
    }

    private JSONArray buildBlocks(List<CoupangMarketplaceOrder> newOrders,
                                  List<CoupangMarketplaceOrder> reminders,
                                  List<CoupangMarketplaceOrder> cancelled,
                                  int wingProcessedCount,
                                  Map<Long, List<CoupangMarketplaceOrderItem>> itemsByOrderId,
                                  int remaining, LocalDateTime now) {
        JSONArray blocks = new JSONArray();
        String headerText;
        if (!newOrders.isEmpty()) {
            headerText = "🛒 판매자배송 신규 주문 " + newOrders.size() + "건";
        } else if (!reminders.isEmpty()) {
            headerText = "⏰ 판매자배송 미처리 주문 " + reminders.size() + "건";
        } else {
            headerText = "🚫 판매자배송 취소 감지 " + cancelled.size() + "건";
        }
        blocks.put(new JSONObject().put("type", "header").put("text", new JSONObject()
                .put("type", "plain_text").put("text", headerText).put("emoji", true)));
        blocks.put(contextBlock(now.format(DIGEST_HEADER_TIME) + " 발송"));
        blocks.put(divider());

        for (CoupangMarketplaceOrder order : newOrders) {
            appendOrderBlocks(blocks, order, itemsByOrderId, null);
        }

        if (!reminders.isEmpty()) {
            if (!newOrders.isEmpty()) { // 신규 0건이면 헤더가 이미 미처리라 소제목 생략
                blocks.put(new JSONObject().put("type", "section").put("text", new JSONObject()
                        .put("type", "mrkdwn")
                        .put("text", "*⏰ 미처리 주문 " + reminders.size() + "건* — 이전 알림 후 아직 미출고입니다")));
                blocks.put(divider());
            }
            for (CoupangMarketplaceOrder order : reminders) {
                appendOrderBlocks(blocks, order, itemsByOrderId, now);
            }
        }

        if (!cancelled.isEmpty()) {
            blocks.put(new JSONObject().put("type", "section").put("text", new JSONObject()
                    .put("type", "mrkdwn")
                    .put("text", "*🚫 취소/반품 감지 " + cancelled.size() + "건* — 포장·발송하지 마세요")));
            List<CoupangMarketplaceOrder> shownCancelled = cancelled.size() > MAX_CARD_CANCELLED
                    ? cancelled.subList(0, MAX_CARD_CANCELLED) : cancelled;
            for (CoupangMarketplaceOrder order : shownCancelled) {
                blocks.put(contextBlock(itemSummary(order, itemsByOrderId) + " · 주문번호 " + order.getOrderId()));
            }
            if (cancelled.size() > shownCancelled.size()) {
                blocks.put(contextBlock("외 " + (cancelled.size() - shownCancelled.size()) + "건"));
            }
            blocks.put(divider());
        }

        if (remaining > 0) {
            blocks.put(contextBlock("외 " + remaining + "건 — 다음 발송에 포함됩니다"));
        }
        if (wingProcessedCount > 0) {
            blocks.put(contextBlock("✅ Wing에서 처리된 주문 " + wingProcessedCount + "건은 목록에서 제외했습니다"));
        }
        String footer = orderScreenUrl != null && !orderScreenUrl.isBlank()
                ? "출고 처리 → <" + orderScreenUrl + "|쿠팡 주문 화면>"
                : "출고 처리 → 쿠팡 주문 화면 (jack.coderecipe.io)";
        blocks.put(contextBlock(footer));
        return blocks;
    }

    /** 주문 1건 = 상품 section + 결제·주문번호 context + divider. nowForElapsed가 있으면 경과일 표기(미처리용) */
    private void appendOrderBlocks(JSONArray blocks, CoupangMarketplaceOrder order,
                                   Map<Long, List<CoupangMarketplaceOrderItem>> itemsByOrderId,
                                   LocalDateTime nowForElapsed) {
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
        String context = "결제 " + paidText + " · 주문번호 " + order.getOrderId();
        if (nowForElapsed != null && order.getPaidAt() != null) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                    order.getPaidAt().toLocalDate(), nowForElapsed.toLocalDate());
            context += days <= 0 ? " · 오늘 결제" : " · ⏰ " + days + "일 경과";
        }
        blocks.put(contextBlock(context));
        blocks.put(divider());
    }

    private String buildFallbackText(List<CoupangMarketplaceOrder> newOrders,
                                     List<CoupangMarketplaceOrder> reminders,
                                     List<CoupangMarketplaceOrder> cancelled,
                                     Map<Long, List<CoupangMarketplaceOrderItem>> itemsByOrderId, int remaining) {
        StringBuilder text = new StringBuilder("🛒 판매자배송 신규 " + newOrders.size() + "건");
        if (!reminders.isEmpty()) {
            text.append(" · ⏰ 미처리 ").append(reminders.size()).append("건");
        }
        if (!cancelled.isEmpty()) {
            text.append(" · 🚫 취소 ").append(cancelled.size()).append("건");
        }
        for (CoupangMarketplaceOrder order : newOrders) {
            text.append("\n· ").append(itemSummary(order, itemsByOrderId))
                    .append(" (주문번호 ").append(order.getOrderId()).append(")");
        }
        for (CoupangMarketplaceOrder order : reminders) {
            text.append("\n⏰ ").append(itemSummary(order, itemsByOrderId))
                    .append(" (주문번호 ").append(order.getOrderId()).append(")");
        }
        for (CoupangMarketplaceOrder order : cancelled) {
            text.append("\n🚫 ").append(itemSummary(order, itemsByOrderId))
                    .append(" (주문번호 ").append(order.getOrderId()).append(")");
        }
        if (remaining > 0) {
            text.append("\n외 ").append(remaining).append("건");
        }
        return text.toString();
    }

    private String itemSummary(CoupangMarketplaceOrder order,
                               Map<Long, List<CoupangMarketplaceOrderItem>> itemsByOrderId) {
        List<CoupangMarketplaceOrderItem> items = itemsByOrderId.getOrDefault(order.getOrderId(), List.of());
        return items.isEmpty()
                ? order.getTotalQuantity() + "개"
                : items.stream().map(i -> (i.getProductName() != null ? i.getProductName() : "(상품명 없음)")
                        + " " + i.getQuantity() + "개").collect(Collectors.joining(" + "));
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
