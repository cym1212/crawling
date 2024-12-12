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


    private final ResourceLoader resourceLoader;
    private final HottracksPythonScriptExecutor hottracksPythonScriptExecutor;
    private final LibroPythonScriptExecutor libroPythonScriptExecutor;
    private final ArcnbookPythonScriptExecutor arcnbookPythonScriptExecutor;

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


}
