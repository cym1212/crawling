package codeRecipe.crawling.crawling;

import codeRecipe.crawling.crawling.arcnbook.ArcnbookPythonScriptExecutor;
import codeRecipe.crawling.crawling.hottracks.HottracksPythonScriptExecutor;
import codeRecipe.crawling.crawling.libro.LibroPythonScriptExecutor;
import lombok.RequiredArgsConstructor;

import org.springframework.core.io.ResourceLoader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CrawlingController {

    //todo 필독 사항 메모 (코드를 손봐야 하는 경우)
    // 1. 업체 종류가 추가될 경우 코드 수정해야함


    //todo
    //  1. ci/cd 코드 손보기
    //  2. 리브로, 아크앤북 로그인 오류 발생시 재시도하도록 코드 수정
    //


    private final ResourceLoader resourceLoader;
    private final HottracksPythonScriptExecutor hottracksPythonScriptExecutor;
    private final LibroPythonScriptExecutor libroPythonScriptExecutor;
    private final ArcnbookPythonScriptExecutor arcnbookPythonScriptExecutor;
    private final SlackWebhookService slackWebhookService;
    private final DataProcessingService dataProcessingService;

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

    @PostMapping("/slack")
    public void sendMessageToSlack() {
        slackWebhookService.sendMessageToSlack();
    }

    @PostMapping("test")
    public String  test(){
        return dataProcessingService.DataProcessing();
    }


}
