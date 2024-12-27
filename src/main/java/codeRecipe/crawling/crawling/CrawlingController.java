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
import org.springframework.data.repository.query.Param;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CrawlingController {

    //todo
    //  1. ci/cd 코드 손보기
    //  2. 리브로, 아크앤북 로그인 오류 발생시 재시도하도록 코드 수정
    //


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

    @PostMapping("/slack")
    public void sendMessageToSlack() {
        slackWebhookService.sendMessageToSlack();
    }

    @PostMapping("test")
    public String  test(){
        return dataProcessingService.DataProcessing();
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
