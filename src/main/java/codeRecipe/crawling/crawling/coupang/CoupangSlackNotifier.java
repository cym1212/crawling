package codeRecipe.crawling.crawling.coupang;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 쿠팡 전용 슬랙 알림. 기존 서점 크롤링 채널(slack.webhook.alertbot.url)과 분리되어 있으며,
 * coupang.slack.webhook-url이 비어 있으면 발송하지 않는다 (로그만 남김).
 * 쿠팡 전용 웹훅이 준비되면 yml에 URL만 넣으면 연결된다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CoupangSlackNotifier {

    private final RestTemplate restTemplate;

    @Value("${coupang.slack.webhook-url:}")
    private String webhookUrl;

    @Value("${coupang.slack.order-webhook-url:}")
    private String orderWebhookUrl;

    /** 부족 재고/수집 경고/잡 실패 알림. @return 실제 발송했으면 true (웹훅 미설정 시 false) */
    public boolean send(String text) {
        return sendTo(webhookUrl, text, "쿠팡 알림");
    }

    /** 판매자배송 신규 주문 알림 (별도 웹훅 키 — 미설정 시 발송 안 함) */
    public boolean sendOrderAlert(String text) {
        return sendTo(orderWebhookUrl, text, "판매자배송 주문 알림");
    }

    private boolean sendTo(String url, String text, String label) {
        if (url == null || url.isBlank()) {
            log.info("{} 웹훅 미설정 - 발송 생략. 메시지:\n{}", label, text);
            return false;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        // JSONObject로 페이로드 생성 — 상품명의 따옴표/개행이 JSON을 깨뜨리지 않도록
        String payload = new JSONObject().put("text", text).toString();
        restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
        return true;
    }
}
