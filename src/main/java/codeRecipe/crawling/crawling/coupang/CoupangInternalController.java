package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.coupang.dto.InternalInventoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "2. 쿠팡 실시간 조회 (30-we.com 연동용)",
        description = "30-we.com 화면의 '지금 새로고침' 버튼이 호출하는 내부 API. "
                + "DB 스냅샷을 거치지 않고 쿠팡을 그 자리에서 호출해 최신 재고를 반환한다.")
@RestController
@RequestMapping("/internal/coupang")
@RequiredArgsConstructor
@Slf4j
public class CoupangInternalController {

    private final CoupangLiveInventoryService liveInventoryService;
    private final CoupangInternalAuth internalAuth;

    @Operation(summary = "실시간 재고 조회",
            description = "쿠팡 로켓그로스 재고를 라이브로 전체 페이징 조회해서 즉시 반환하고, "
                    + "조회 결과는 coupang_inventory 스냅샷에도 반영한다 (마지막 동기화 시각 갱신. 저장 실패해도 응답은 정상). "
                    + "60초 TTL 캐시가 있어 연속 호출 시 두 번째부터는 즉시 응답하며, 여러 사용자가 동시에 눌러도 "
                    + "쿠팡 호출은 1회만 나간다. 오류: 토큰 불일치 401 {\"error\":\"unauthorized\"}, "
                    + "쿠팡 장애/키 미활성화 502 {\"error\":\"coupang_api_error\"}.")
    @GetMapping("/inventory")
    public ResponseEntity<Object> inventory(
            @Parameter(description = "내부 인증 토큰 (application-coupang.yml의 coupang.internal.token 값)")
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
