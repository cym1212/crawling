package codeRecipe.crawling.crawling;


import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;


@Service
@RequiredArgsConstructor
public class SlackWebhookService {
    private final RestTemplate restTemplate;
    private final DataProcessingService dataProcessingService;


    @Value("${slack.webhook.alertbot.url}")
    private String webhookUrl;


    public void sendMessageToSlackDailyData() {
        HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Content-Type", "application/json");


        String message = dataProcessingService.dailyDataProcessing();

        String payload = "{\"text\": \"" + message + "\"}";

        HttpEntity<String> request = new HttpEntity<>(payload, headers);

        // HTTP POST 요청 실행
        ResponseEntity<String> response = restTemplate.exchange(
                webhookUrl, // Webhook URL
                HttpMethod.POST,   // HTTP 메서드
                request,           // 요청 데이터
                String.class       // 응답 타입
        );

        // 응답 결과 출력
        if (response.getStatusCode().is2xxSuccessful()) {
            System.out.println("Slack message sent successfully.");
        } else {
            System.err.println("Failed to send Slack message. Response: " + response.getBody());
        }
    }


    public void sendMessageToSlackWeeklyData() {
        // HTTP 헤더 설정
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");

        // JSON 메시지 생성
        String message = dataProcessingService.weeklyDataProcessing();

        // HttpEntity에 바로 JSON 메시지를 넣기
        HttpEntity<String> request = new HttpEntity<>(message, headers);

        // HTTP POST 요청 실행
        ResponseEntity<String> response = restTemplate.exchange(
                webhookUrl,       // Webhook URL
                HttpMethod.POST,  // HTTP 메서드
                request,          // 요청 데이터
                String.class      // 응답 타입
        );

        // 응답 결과 출력
        if (response.getStatusCode().is2xxSuccessful()) {
            System.out.println("Slack message sent successfully.");
        } else {
            System.err.println("Failed to send Slack message. Response: " + response.getBody());
        }
    }


}
