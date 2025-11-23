package codeRecipe.crawling.crawling;

import codeRecipe.crawling.crawling.arcnbook.ArcnbookPythonScriptExecutor;
import codeRecipe.crawling.crawling.hottracks.HottracksPythonScriptExecutor;
import codeRecipe.crawling.crawling.hyggebook.HyggebookPythonScriptExecutor;
import codeRecipe.crawling.crawling.libro.LibroPythonScriptExecutor;
import codeRecipe.crawling.crawling.yeongpoong.YeongpoongPythonScriptExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class SchedulingService {
    private final HottracksPythonScriptExecutor hottracksPythonScriptExecutor;
    private final LibroPythonScriptExecutor libroPythonScriptExecutor;
    private final ArcnbookPythonScriptExecutor arcnbookPythonScriptExecutor;
    private final SlackWebhookService slackWebhookService;
    private final HyggebookPythonScriptExecutor hyggebookPythonScriptExecutor;
    private final YeongpoongPythonScriptExecutor yeongpoongPythonScriptExecutor;

    // 크롤링 실패 목록 저장
    private java.util.List<String> lastCrawlingFailures = new java.util.ArrayList<>();


    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask1() {
        executeCrawlingWithErrorTracking();
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask2() {
        executeCrawlingWithErrorTracking();
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask3() {
        executeCrawlingWithErrorTracking();
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask4() {
        executeCrawlingWithErrorTracking();
    }

    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask5() {
        executeCrawlingWithErrorTracking();
    }

    // 크롤링 실행 및 오류 추적 공통 메서드 (스케줄러 + 테스트 엔드포인트에서 사용)
    public void executeCrawlingWithErrorTracking() {
        // 새로운 크롤링 시작 전 이전 실패 목록 초기화
        lastCrawlingFailures.clear();

        java.util.List<String> failedSites = new java.util.ArrayList<>();

        // 핫트랙스 크롤링
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            log.info("핫트랙스 크롤링 성공");
        } catch (Exception e) {
            failedSites.add("핫트랙스");
            log.error("핫트랙스 크롤링 실패: {}", e.getMessage(), e);
        }

        // 아크앤북 크롤링
        try {
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();
            log.info("아크앤북 크롤링 성공");
        } catch (Exception e) {
            failedSites.add("아크앤북");
            log.error("아크앤북 크롤링 실패: {}", e.getMessage(), e);
        }

        // 휘게문고 크롤링
        try {
            String hyggebook = hyggebookPythonScriptExecutor.excutePythonScript();
            log.info("휘게문고 크롤링 성공");
        } catch (Exception e) {
            failedSites.add("휘게문고");
            log.error("휘게문고 크롤링 실패: {}", e.getMessage(), e);
        }

        // 영풍문고 크롤링
        try {
            String yeongpoong = yeongpoongPythonScriptExecutor.excutePythonScript();
            log.info("영풍문고 크롤링 성공");
        } catch (Exception e) {
            failedSites.add("영풍문고");
            log.error("영풍문고 크롤링 실패: {}", e.getMessage(), e);
        }

        // 실패 목록 저장 (10시에 슬랙으로 전송용)
        this.lastCrawlingFailures = failedSites;

        if (failedSites.isEmpty()) {
            log.info("모든 크롤링 완료");
        } else {
            log.warn("크롤링 실패한 사이트: {}", String.join(", ", failedSites));
        }
    }


    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Seoul")
    public void sendMessageToSlackDailyData() {
        synchronized (this) {
            try {
                // 크롤링 실패가 있으면 먼저 오류 알림 전송
                if (!lastCrawlingFailures.isEmpty()) {
                    slackWebhookService.sendCrawlingErrorAlert(lastCrawlingFailures);
                    log.info("크롤링 오류 알림 전송 완료: {}", String.join(", ", lastCrawlingFailures));
                }

                // 정상 데이터 리포트 전송 (성공한 회사들의 데이터)
                slackWebhookService.sendMessageToSlackDailyData();
                log.info("일일 데이터 리포트 전송 완료");
            } catch (Exception e) {
                log.error("Error occurred while sending message to Slack: {}", e.getMessage(), e);
            }
        }
    }
    
    @Scheduled(cron = "0 1 10 * * 1", zone = "Asia/Seoul")
    public void sendMessageToSlackWeeklyData() {
        synchronized (this) {
            try {
                slackWebhookService.sendMessageToSlackWeeklyData();
            } catch (Exception e) {
                log.error("Error occurred while sending message to Slack: {}", e.getMessage(), e);
            }
        }
    }

    // 크롤링 실패 목록 조회 메서드 (테스트 엔드포인트에서 사용)
    public java.util.List<String> getLastCrawlingFailures() {
        return new java.util.ArrayList<>(lastCrawlingFailures);
    }

}
