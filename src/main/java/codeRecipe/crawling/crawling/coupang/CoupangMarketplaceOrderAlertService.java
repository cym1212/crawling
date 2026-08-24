package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.domain.CoupangMarketplaceOrder;
import codeRecipe.crawling.crawling.repository.CoupangMarketplaceOrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 판매자배송(마켓플레이스) 신규 주문 감지 → 슬랙 알림.
 * 폴링마다 최근 24시간 창의 결제완료(ACCEPT) 발주서를 조회하고,
 * 이미 알림 보낸 주문(coupang_marketplace_order)은 건너뛴다.
 * 참고: 판매자가 10분(폴링 주기) 안에 상품준비중으로 넘긴 주문은 ACCEPT에서 빠져 알림이 생략될 수 있다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangMarketplaceOrderAlertService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME_PARAM = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int MAX_PAGES = 50;

    private final CoupangApiClient coupangApiClient;
    private final CoupangMarketplaceOrderRepository marketplaceOrderRepository;
    private final CoupangSlackNotifier slackNotifier;

    public record NewOrder(long orderId, String ordererName, LocalDateTime paidAt, List<String> itemLines) {
    }

    public record AlertRunResult(int newOrderCount, boolean slackSent, String message, List<NewOrder> orders) {
    }

    /** 발주서 응답 data 배열에서 주문 정보를 추출한다 (순수 함수 — 단위 테스트 대상) */
    public static List<NewOrder> parseOrders(JsonNode dataArray) {
        List<NewOrder> orders = new ArrayList<>();
        for (JsonNode orderNode : dataArray) {
            long orderId = orderNode.path("orderId").asLong(0);
            if (orderId <= 0) {
                continue;
            }
            String ordererName = CoupangJsonUtils.textOrNull(orderNode.path("orderer"), "name");
            LocalDateTime paidAt = parseIsoDateTime(CoupangJsonUtils.textOrNull(orderNode, "paidAt"));
            List<String> itemLines = new ArrayList<>();
            for (JsonNode itemNode : orderNode.path("orderItems")) {
                String itemName = CoupangJsonUtils.textOrNull(itemNode, "vendorItemName");
                int count = itemNode.path("shippingCount").asInt(0);
                itemLines.add((itemName != null ? itemName : "(상품명 없음)") + " — " + count + "개");
            }
            orders.add(new NewOrder(orderId, ordererName, paidAt, itemLines));
        }
        return orders;
    }

    private static LocalDateTime parseIsoDateTime(String text) {
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(SEOUL).toLocalDateTime();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(text.length() > 19 ? text.substring(0, 19) : text);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /**
     * 신규 결제완료 주문 확인.
     * @param dryRun true면 슬랙 발송·중복방지 기록 없이 감지 결과만 반환
     */
    public AlertRunResult checkAndNotify(boolean dryRun) {
        LocalDateTime now = LocalDateTime.now(SEOUL);
        String from = now.minusHours(24).format(TIME_PARAM) + "%2B09:00";
        String to = now.format(TIME_PARAM) + "%2B09:00";

        List<NewOrder> allOrders = new ArrayList<>();
        String nextToken = null;
        int pages = 0;
        do {
            JsonNode response = coupangApiClient.getMarketplaceOrderSheets(from, to, "ACCEPT");
            JsonNode data = response.path("data");
            if (!data.isArray()) {
                throw new CoupangApiException(
                        "쿠팡 발주서 응답 형식 오류: " + response.path("message").asText(""), null, null);
            }
            allOrders.addAll(parseOrders(data));
            nextToken = CoupangJsonUtils.textOrNull(response, "nextToken");
            pages++;
        } while (nextToken != null && pages < MAX_PAGES);

        // 이미 알림 보낸 주문 제외
        List<NewOrder> newOrders = new ArrayList<>();
        for (NewOrder order : allOrders) {
            if (!marketplaceOrderRepository.existsByOrderId(order.orderId())) {
                newOrders.add(order);
            }
        }

        if (newOrders.isEmpty()) {
            log.info("판매자배송 신규 주문 없음 (조회 {}건, 전부 기알림)", allOrders.size());
            return new AlertRunResult(0, false, "신규 주문 없음", List.of());
        }

        String message = buildMessage(newOrders);
        boolean sent = false;
        if (!dryRun) {
            sent = slackNotifier.sendOrderAlert(message);
            LocalDateTime notifiedAt = now;
            List<CoupangMarketplaceOrder> records = new ArrayList<>();
            for (NewOrder order : newOrders) {
                records.add(CoupangMarketplaceOrder.builder()
                        .orderId(order.orderId())
                        .paidAt(order.paidAt())
                        .orderSummary(String.join(" / ", order.itemLines()))
                        .notifiedAt(notifiedAt)
                        .build());
            }
            marketplaceOrderRepository.saveAll(records);
        }
        log.info("판매자배송 신규 주문 {}건 감지 (dryRun={}, slackSent={})", newOrders.size(), dryRun, sent);
        return new AlertRunResult(newOrders.size(), sent, message, newOrders);
    }

    private String buildMessage(List<NewOrder> newOrders) {
        StringBuilder message = new StringBuilder();
        message.append("🛒 *새 판매자배송 주문* ").append(newOrders.size()).append("건\n\n");
        int index = 1;
        for (NewOrder order : newOrders) {
            for (String itemLine : order.itemLines()) {
                message.append(String.format("%d. %s\n", index, itemLine));
            }
            message.append("   주문자 ").append(order.ordererName() != null ? order.ordererName() : "-")
                    .append(" / 결제 ").append(order.paidAt() != null ? order.paidAt().format(DISPLAY) : "-")
                    .append(" / 주문번호 ").append(order.orderId())
                    .append("\n\n");
            index++;
        }
        message.append("Wing > 주문/배송 관리에서 출고 처리해주세요.");
        return message.toString();
    }
}
