package codeRecipe.crawling.crawling;

import codeRecipe.crawling.crawling.arcnbook.ArcnbookPythonScriptExecutor;
import codeRecipe.crawling.crawling.batch.ArcnbookBatchExecutor;
import codeRecipe.crawling.crawling.batch.HottracksBatchExecutor;
import codeRecipe.crawling.crawling.batch.HyggebookBatchExecutor;
import codeRecipe.crawling.crawling.batch.LibroBatchExecutor;
import codeRecipe.crawling.crawling.batch.YeongpoongBatchExecutor;
import codeRecipe.crawling.crawling.hottracks.HottracksPythonScriptExecutor;
import codeRecipe.crawling.crawling.hyggebook.HyggebookPythonScriptExecutor;
import codeRecipe.crawling.crawling.libro.LibroPythonScriptExecutor;
import codeRecipe.crawling.crawling.yeongpoong.YeongpoongPythonScriptExecutor;
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
    private final YeongpoongPythonScriptExecutor yeongpoongPythonScriptExecutor;
    private final SlackWebhookService slackWebhookService;
    private final DataProcessingService dataProcessingService;
    private final SchedulingService schedulingService;
    private final HottracksBatchExecutor hottracksBatchExecutor;
    private final ArcnbookBatchExecutor arcnbookBatchExecutor;
    private final LibroBatchExecutor libroBatchExecutor;
    private final HyggebookBatchExecutor hyggebookBatchExecutor;
    private final YeongpoongBatchExecutor yeongpoongBatchExecutor;

    @PostMapping("/hottracks")
    public String  hottracks() throws Exception {

        return hottracksPythonScriptExecutor.excutePythonScript();
    }

//    @PostMapping("/libro")
//    public String  libro() throws Exception {
//
//        return libroPythonScriptExecutor.excutePythonScript();
//    }

    @PostMapping("/arcnbook")
    public String  arcnbook() throws Exception {

        return arcnbookPythonScriptExecutor.excutePythonScript();
    }
    @PostMapping("/hyggebook")
    public String  hyggebook() throws Exception {

        return hyggebookPythonScriptExecutor.excutePythonScript();
    }

    @PostMapping("/yeongpoong")
    public String  yeongpoong() throws Exception {

        return yeongpoongPythonScriptExecutor.excutePythonScript();
    }

    @PostMapping("/crawling/all")
    public String executeCrawlingAll() {
        schedulingService.executeCrawlingWithErrorTracking();
        return "전체 크롤링 실행 완료. /daily/test 엔드포인트에서 결과를 확인하세요.";
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
        StringBuilder result = new StringBuilder();

        // 크롤링 실패 목록 확인 및 추가
        java.util.List<String> failures = schedulingService.getLastCrawlingFailures();
        if (!failures.isEmpty()) {
            result.append("⚠️ 크롤링 오류 발생\n");
            result.append(String.join(", ", failures));
            result.append(" 사이트에서 오류가 발생했습니다.\n\n");
            result.append("---\n\n");
        }

        // 정상 데이터 처리 결과 추가
        result.append(dataProcessingService.dailyDataProcessing());

        return result.toString();
    }
    @GetMapping("/weekly/test")
    public String  weeklyTest() throws Exception {
        return dataProcessingService.weeklyDataProcessingText();
    }


   

    @PostMapping("/batch/hottracks")
    public void batchHottracks(@RequestParam String startDate, @RequestParam String endDate) throws Exception {
         hottracksBatchExecutor.executeForDateRange(startDate,endDate);
    }
//    @PostMapping("/batch/libro")
//    public void batchLibro(@RequestParam String startDate, @RequestParam String endDate) throws Exception {
//         libroBatchExecutor.executeForDateRange(startDate,endDate);
//    }
    @PostMapping("/batch/arcnbook")
    public void batchArcnbook(@RequestParam String startDate, @RequestParam String endDate) throws Exception {
         arcnbookBatchExecutor.executeForDateRange(startDate,endDate);
    }
    @PostMapping("/batch/hygge")
    public void batchHygge(@RequestParam String startDate, @RequestParam String endDate) throws Exception {
         hyggebookBatchExecutor.executeForDateRange(startDate,endDate);
    }

    @PostMapping("/batch/yeongpoong")
    public void batchYeongpoong(@RequestParam String startDate, @RequestParam String endDate) throws Exception {
         yeongpoongBatchExecutor.executeForDateRange(startDate,endDate);
    }


}
