package codeRecipe.crawling.crawling;

import codeRecipe.crawling.crawling.arcnbook.ArcnbookPythonScriptExecutor;
import codeRecipe.crawling.crawling.hottracks.HottracksPythonScriptExecutor;
import codeRecipe.crawling.crawling.libro.LibroPythonScriptExecutor;
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

    @Scheduled(cron = "0 0 1 * * * ", zone = "Asia/Seoul")
//    @Scheduled(cron = "0 * * * * ?", zone = "Asia/Seoul")
    public void executeScheduledTask1() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 1 30 * *", zone = "Asia/Seoul")
    public void executeScheduledTask2() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);


        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask3() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 2 30 * *", zone = "Asia/Seoul")
    public void executeScheduledTask4() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask5() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask6() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask7() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }@Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask8() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }@Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    public void executeScheduledTask9() {
        try {
            String hottracks = hottracksPythonScriptExecutor.excutePythonScript();
            String libro = libroPythonScriptExecutor.excutePythonScript();
            String arcnbook = arcnbookPythonScriptExecutor.excutePythonScript();

            System.out.println("Success: " + hottracks);
            System.out.println("Success: " + libro);
            System.out.println("Success: " + arcnbook);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    //
//    @Scheduled(cron = "0 0 11 * * *", zone = "Asia/Seoul")
//    public  void sendMessageToSlack() {
//        log.debug("sendMessageToSlack scheduled task started at: {}", LocalDateTime.now());
//        try {
//            slackWebhookService.sendMessageToSlack();
//            log.info("Slack message sent successfully at: {}", LocalDateTime.now());
//        } catch (Exception e) {
//            log.error("Error occurred while sending message to Slack: {}", e.getMessage(), e);
//        }
//        log.debug("sendMessageToSlack scheduled task ended at: {}", LocalDateTime.now());
//    }


    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Seoul")
    public void sendMessageToSlack() {
        log.info("Task started on thread: {}, at: {}", Thread.currentThread().getName(), LocalDateTime.now());
        synchronized (this) {
            try {
                slackWebhookService.sendMessageToSlack();
                log.info("Task completed on thread: {}, at: {}", Thread.currentThread().getName(), LocalDateTime.now());
            } catch (Exception e) {
                log.error("Error occurred while sending message to Slack: {}", e.getMessage(), e);
            }
        }
    }
//    @Scheduled(cron = "0 22 14 * * *", zone = "Asia/Seoul")
//    public void sendMessageToSlack1() {
//        log.info("Task started on thread: {}, at: {}", Thread.currentThread().getName(), LocalDateTime.now());
//        synchronized (this) {
//            try {
//                slackWebhookService.sendMessageToSlack();
//                log.info("Task completed on thread: {}, at: {}", Thread.currentThread().getName(), LocalDateTime.now());
//            } catch (Exception e) {
//                log.error("Error occurred while sending message to Slack: {}", e.getMessage(), e);
//            }
//        }
//    }


}
