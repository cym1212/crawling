package codeRecipe.crawling.crawling;

import codeRecipe.crawling.crawling.arcnbook.ArcnbookPythonScriptExecutor;
import codeRecipe.crawling.crawling.batch.ArcnbookBatchExecutor;
import codeRecipe.crawling.crawling.batch.HottracksBatchExecutor;
import codeRecipe.crawling.crawling.batch.HyggebookBatchExecutor;
import codeRecipe.crawling.crawling.batch.LibroBatchExecutor;
import codeRecipe.crawling.crawling.hottracks.HottracksPythonScriptExecutor;
import codeRecipe.crawling.crawling.hyggebook.HyggebookPythonScriptExecutor;
import codeRecipe.crawling.crawling.libro.LibroPythonScriptExecutor;
import lombok.RequiredArgsConstructor;

import org.springframework.core.io.ResourceLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CrawlingController {

    //todo
    //  1. ci/cd 코드 손보기
    //  2. 데이터 1년치 돌리고 getTargetDate() 원복하기



    private final ResourceLoader resourceLoader;
    private final HottracksPythonScriptExecutor hottracksPythonScriptExecutor;
    private final LibroPythonScriptExecutor libroPythonScriptExecutor;
    private final ArcnbookPythonScriptExecutor arcnbookPythonScriptExecutor;
    private final HyggebookPythonScriptExecutor hyggebookPythonScriptExecutor;
    private final SlackWebhookService slackWebhookService;
    private final DataProcessingService dataProcessingService;
    private final HottracksBatchExecutor hottracksBatchExecutor;
    private final ArcnbookBatchExecutor arcnbookBatchExecutor;
    private final LibroBatchExecutor libroBatchExecutor;
    private final HyggebookBatchExecutor hyggebookBatchExecutor;

    @PostMapping("/hottracks")
    public String  hottracks() throws Exception {

        return hottracksPythonScriptExecutor.excutePythonScript();
    }

    @PostMapping("/libro")
    public String  libro() throws Exception {

        return libroPythonScriptExecutor.excutePythonScript();
    }

    @PostMapping("/arcnbook")
    public String  arcnbook() throws Exception {

        return arcnbookPythonScriptExecutor.excutePythonScript();
    }
    @PostMapping("/hyggebook")
    public String  hyggebook() throws Exception {

        return hyggebookPythonScriptExecutor.excutePythonScript();
    }

    @PostMapping("/daily/slack")
    public void sendMessageToSlackDaily() {
        slackWebhookService.sendMessageToSlackDailyData();
    }

    @PostMapping("/weekly/slack")
    public void sendMessageToSlackWeekly() {
        slackWebhookService.sendMessageToSlackWeeklyData();
    }

    @GetMapping("/daily/test")
    public String  dailyTest() throws Exception {
        return dataProcessingService.dailyDataProcessing();
    }
    @GetMapping("/weekly/test")
    public String  weeklyTest() throws Exception {
        return dataProcessingService.weeklyDataProcessing();
    }


    @PostMapping("/batch/hottracks")
    public void batchHottracks(@RequestParam String startDate, @RequestParam String endDate) throws Exception {
         hottracksBatchExecutor.executeForDateRange(startDate,endDate);
    }
    @PostMapping("/batch/libro")
    public void batchLibro(@RequestParam String startDate, @RequestParam String endDate) throws Exception {
         libroBatchExecutor.executeForDateRange(startDate,endDate);
    }
    @PostMapping("/batch/arcnbook")
    public void batchArcnbook(@RequestParam String startDate, @RequestParam String endDate) throws Exception {
         arcnbookBatchExecutor.executeForDateRange(startDate,endDate);
    }
    @PostMapping("/batch/hygge")
    public void batchHygge(@RequestParam String startDate, @RequestParam String endDate) throws Exception {
         hyggebookBatchExecutor.executeForDateRange(startDate,endDate);
    }


}
