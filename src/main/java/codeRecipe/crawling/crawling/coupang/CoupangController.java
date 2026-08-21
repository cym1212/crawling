package codeRecipe.crawling.crawling.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "3. 쿠팡 디버그",
        description = "개발/디버깅용. /admin/** 경로라 관리자 로그인 세션이 필요하다 (Swagger에서 바로 실행하려면 "
                + "같은 브라우저에서 먼저 /login으로 로그인).")
@RestController
@RequestMapping("/admin/coupang")
@RequiredArgsConstructor
public class CoupangController {

    private final CoupangApiClient coupangApiClient;

    @Operation(summary = "재고 API 원본 응답 조회 (1페이지)",
            description = "쿠팡 로켓창고 재고 API의 첫 페이지 응답 JSON을 가공 없이 그대로 반환한다. "
                    + "응답 필드 구조 확인용 디버그 엔드포인트.")
    @GetMapping("/inventory")
    public JsonNode inventory() {
        return coupangApiClient.getRocketGrowthInventorySummaries();
    }
}
