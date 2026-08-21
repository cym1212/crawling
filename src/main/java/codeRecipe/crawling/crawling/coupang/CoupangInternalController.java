package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.coupang.dto.InternalInventoryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 30-we.com이 호출하는 내부 실시간 API.
 * 응답/오류 형식은 docs/30we-기능개발-의뢰서.md §1-6 계약과 일치해야 한다.
 */
@RestController
@RequestMapping("/internal/coupang")
@RequiredArgsConstructor
@Slf4j
public class CoupangInternalController {

    private final CoupangLiveInventoryService liveInventoryService;
    private final CoupangInternalAuth internalAuth;

    @GetMapping("/inventory")
    public ResponseEntity<Object> inventory(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            InternalInventoryResponse response = liveInventoryService.getInventory();
            return ResponseEntity.ok(response);
        } catch (CoupangApiException e) {
            log.error("내부 실시간 재고 API 조회 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "coupang_api_error",
                    "message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }
}
