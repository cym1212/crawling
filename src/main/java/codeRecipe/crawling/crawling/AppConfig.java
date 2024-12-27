package codeRecipe.crawling.crawling;

import codeRecipe.crawling.crawling.arcnbook.ArcnbookPythonScriptExecutor;
import codeRecipe.crawling.crawling.batch.ArcnbookBatchExecutor;
import codeRecipe.crawling.crawling.batch.HottracksBatchExecutor;
import codeRecipe.crawling.crawling.batch.HyggebookBatchExecutor;
import codeRecipe.crawling.crawling.batch.LibroBatchExecutor;
import codeRecipe.crawling.crawling.hottracks.HottracksPythonScriptExecutor;
import codeRecipe.crawling.crawling.hyggebook.HyggebookPythonScriptExecutor;
import codeRecipe.crawling.crawling.libro.LibroPythonScriptExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
    @Bean
    public HottracksBatchExecutor hottracksBatchExecutor(HottracksPythonScriptExecutor hottracksPythonScriptExecutor) {
        return new HottracksBatchExecutor(hottracksPythonScriptExecutor);
    }
    @Bean
    public LibroBatchExecutor libroBatchExecutor(LibroPythonScriptExecutor libroPythonScriptExecutor) {
        return new LibroBatchExecutor(libroPythonScriptExecutor);
    }
    @Bean
    public ArcnbookBatchExecutor arcnbookBatchExecutor(ArcnbookPythonScriptExecutor arcnbookPythonScriptExecutor) {
        return new ArcnbookBatchExecutor(arcnbookPythonScriptExecutor);
    }
    @Bean
    public HyggebookBatchExecutor hyggebookBatchExecutor(HyggebookPythonScriptExecutor hyggebookPythonScriptExecutor) {
        return new HyggebookBatchExecutor(hyggebookPythonScriptExecutor);
    }

}
