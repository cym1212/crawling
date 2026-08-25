package codeRecipe.crawling.crawling.hottracks;

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
 * 교보 발주 전용 슬랙 알림. 기존 서점 크롤링 채널과 분리.
 * hottracks.slack.order-webhook-url 이 비어 있으면 발송하지 않는다 (로그만).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HottracksSlackNotifier {

    private final RestTemplate restTemplate;

    @Value("${hottracks.slack.order-webhook-url:}")
    private String orderWebhookUrl;

    /** 발주 다이제스트/잡 실패 알림. @return 실제 발송했으면 true (웹훅 미설정 시 false) */
    public boolean send(String text) {
        if (orderWebhookUrl == null || orderWebhookUrl.isBlank()) {
            log.info("교보 발주 알림 웹훅 미설정 - 발송 생략. 메시지:\n{}", text);
            return false;
        }
        String payload = new JSONObject().put("text", text).toString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        restTemplate.exchange(orderWebhookUrl, HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
        return true;
    }
}
