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

    @Scheduled(cron = "0 0 1 * * *")
    public void executeScheduledTask() {
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
}
