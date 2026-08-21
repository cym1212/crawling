package codeRecipe.crawling.crawling.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/coupang")
@RequiredArgsConstructor
public class CoupangController {

    private final CoupangApiClient coupangApiClient;

    @GetMapping("/inventory")
    public JsonNode inventory() {
        return coupangApiClient.getRocketGrowthInventorySummaries();
    }
}
