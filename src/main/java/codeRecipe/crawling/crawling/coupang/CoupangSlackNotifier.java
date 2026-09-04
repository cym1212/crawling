package codeRecipe.crawling.crawling.coupang;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
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

    /**
     * Block Kit 카드 발송 (부족 재고 알림용). fallbackText는 푸시 알림 미리보기/미지원 클라이언트용 요약.
     * @return 실제 발송했으면 true (웹훅 미설정 시 false)
     */
    public boolean sendCard(String fallbackText, JSONArray blocks) {
        return sendCardTo(webhookUrl, fallbackText, blocks, "쿠팡 알림");
    }

    /** 판매자배송 주문 다이제스트 카드 발송 (별도 웹훅 키 — 미설정 시 발송 안 함) */
    public boolean sendOrderCard(String fallbackText, JSONArray blocks) {
        return sendCardTo(orderWebhookUrl, fallbackText, blocks, "판매자배송 주문 알림");
    }

    // 슬랙은 메시지당 블록 50개까지만 허용. 연속 페이지 머리의 "…이어서" 1블록을 감안한 안전선.
    private static final int MAX_BLOCKS_PER_MESSAGE = 45;

    /** 부족 재고 카드 — 블록 그룹을 여러 메시지로 나눠 순서대로 발송 (건수 제한 없음) */
    public boolean sendCardPaged(String fallbackText, java.util.List<JSONArray> groups) {
        return sendCardPagedTo(webhookUrl, fallbackText, groups, "쿠팡 알림");
    }

    /** 판매자배송 다이제스트 카드 — 블록 그룹을 여러 메시지로 나눠 순서대로 발송 */
    public boolean sendOrderCardPaged(String fallbackText, java.util.List<JSONArray> groups) {
        return sendCardPagedTo(orderWebhookUrl, fallbackText, groups, "판매자배송 주문 알림");
    }

    /**
     * 블록 그룹(항상 붙어 다녀야 하는 블록 묶음 — 예: 상품 1건의 내용+옵션ID+구분선)들을
     * 45블록 이하 메시지 여러 개로 나눠 순서대로 발송한다. 그룹은 중간에서 쪼개지 않는다.
     * 2번째 메시지부터는 맨 위에 "…이어서 (i/n)"이 붙어 채널에서 이어진 카드로 보인다.
     */
    private boolean sendCardPagedTo(String url, String fallbackText, java.util.List<JSONArray> groups, String label) {
        if (url == null || url.isBlank()) {
            log.info("{} 웹훅 미설정 - 카드 발송 생략. 요약:\n{}", label, fallbackText);
            return false;
        }
        java.util.List<JSONArray> pages = new java.util.ArrayList<>();
        JSONArray current = new JSONArray();
        for (JSONArray group : groups) {
            if (current.length() > 0 && current.length() + group.length() > MAX_BLOCKS_PER_MESSAGE) {
                pages.add(current);
                current = new JSONArray();
            }
            for (int i = 0; i < group.length(); i++) {
                current.put(group.get(i));
            }
        }
        if (current.length() > 0) {
            pages.add(current);
        }

        String firstLine = fallbackText.contains("\n")
                ? fallbackText.substring(0, fallbackText.indexOf('\n')) : fallbackText;
        for (int p = 0; p < pages.size(); p++) {
            JSONArray blocks = pages.get(p);
            String fallback = fallbackText;
            if (p > 0) {
                fallback = firstLine + " (이어서 " + (p + 1) + "/" + pages.size() + ")";
                JSONArray withHead = new JSONArray().put(new JSONObject().put("type", "context").put("elements",
                        new JSONArray().put(new JSONObject().put("type", "mrkdwn")
                                .put("text", "…이어서 (" + (p + 1) + "/" + pages.size() + ")"))));
                for (int i = 0; i < blocks.length(); i++) {
                    withHead.put(blocks.get(i));
                }
                blocks = withHead;
            }
            post(url, new JSONObject().put("text", fallback).put("blocks", blocks).toString());
        }
        return true;
    }

    private boolean sendCardTo(String url, String fallbackText, JSONArray blocks, String label) {
        if (url == null || url.isBlank()) {
            log.info("{} 웹훅 미설정 - 카드 발송 생략. 요약:\n{}", label, fallbackText);
            return false;
        }
        post(url, new JSONObject().put("text", fallbackText).put("blocks", blocks).toString());
        return true;
    }

    private boolean sendTo(String url, String text, String label) {
        if (url == null || url.isBlank()) {
            log.info("{} 웹훅 미설정 - 발송 생략. 메시지:\n{}", label, text);
            return false;
        }
        // JSONObject로 페이로드 생성 — 상품명의 따옴표/개행이 JSON을 깨뜨리지 않도록
        post(url, new JSONObject().put("text", text).toString());
        return true;
    }

    private void post(String url, String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
    }
}
