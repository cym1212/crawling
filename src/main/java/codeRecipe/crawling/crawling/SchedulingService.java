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


    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask1() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();
            String hyggebook = hyggebookPythonScriptExecutor.excutePythonScript();
            String yeongpoong = yeongpoongPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
            System.out.println("Success: " + hyggebook);
            System.out.println("Success: " + yeongpoong);
            log.info("영풍문고 크롤링 완료");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask2() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();
            String hyggebook = hyggebookPythonScriptExecutor.excutePythonScript();
            String yeongpoong = yeongpoongPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
            System.out.println("Success: " + hyggebook);
            System.out.println("Success: " + yeongpoong);
            log.info("영풍문고 크롤링 완료");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask3() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();
            String hyggebook = hyggebookPythonScriptExecutor.excutePythonScript();
            String yeongpoong = yeongpoongPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
            System.out.println("Success: " + hyggebook);
            System.out.println("Success: " + yeongpoong);
            log.info("영풍문고 크롤링 완료");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask4() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();
            String hyggebook = hyggebookPythonScriptExecutor.excutePythonScript();
            String yeongpoong = yeongpoongPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
            System.out.println("Success: " + hyggebook);
            System.out.println("Success: " + yeongpoong);
            log.info("영풍문고 크롤링 완료");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask5() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();
            String hyggebook = hyggebookPythonScriptExecutor.excutePythonScript();
            String yeongpoong = yeongpoongPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
            System.out.println("Success: " + hyggebook);
            System.out.println("Success: " + yeongpoong);
            log.info("영풍문고 크롤링 완료");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }


    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Seoul")
    public void sendMessageToSlackDailyData() {
        synchronized (this) {
            try {
                slackWebhookService.sendMessageToSlackDailyData();
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

}
