package codeRecipe.crawling.crawling;

import codeRecipe.crawling.crawling.arcnbook.ArcnbookPythonScriptExecutor;
import codeRecipe.crawling.crawling.hottracks.HottracksPythonScriptExecutor;
import codeRecipe.crawling.crawling.libro.LibroPythonScriptExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
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

    @Scheduled(cron = "0 0 11 * * *", zone = "Asia/Seoul")
    public void sendMessageToSlack() {
        try {
            slackWebhookService.sendMessageToSlack();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
