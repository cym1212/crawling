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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 30-we.com이 호출하는 내부 API (실시간 재고 조회 + 판매자배송 출고 처리).
 * 응답/오류 형식은 docs/30we-기능개발-의뢰서.md 계약과 일치해야 한다.
 */
@Tag(name = "2. 쿠팡 내부 API (30-we.com 연동용)",
        description = "30-we.com이 호출하는 내부 API. 실시간 재고 조회('지금 새로고침')와 "
                + "판매자배송 출고 처리([쿠팡 등록] 버튼)를 제공한다.")
@RestController
@RequestMapping("/internal/coupang")
@RequiredArgsConstructor
@Slf4j
public class CoupangInternalController {

    private final CoupangLiveInventoryService liveInventoryService;
    private final CoupangFulfillmentService fulfillmentService;
    private final CoupangInternalAuth internalAuth;

    /** 출고 처리 요청 본문 */
    public record ShipRequest(String deliveryCompanyCode, String invoiceNumber) {
    }

    @Operation(summary = "실시간 재고 조회",
            description = "쿠팡 로켓그로스 재고를 라이브로 전체 페이징 조회해서 즉시 반환하고, "
                    + "조회 결과는 coupang_inventory 스냅샷에도 반영한다 (마지막 동기화 시각 갱신. 저장 실패해도 응답은 정상). "
                    + "60초 TTL 캐시가 있어 연속 호출 시 두 번째부터는 즉시 응답하며, 여러 사용자가 동시에 눌러도 "
                    + "쿠팡 호출은 1회만 나간다. 오류: 토큰 불일치 401 {\"error\":\"unauthorized\"}, "
                    + "쿠팡 장애/키 미활성화 502 {\"error\":\"coupang_api_error\"}.")
    @GetMapping("/inventory")
    public ResponseEntity<Object> inventory(
            @Parameter(description = "내부 인증 토큰 (공통 app.internal.token 값(application-internal.yml))")
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

    @Operation(summary = "판매자배송 출고 처리 (준비중 + 송장 등록)",
            description = "30-we.com [쿠팡 등록] 버튼용. 해당 주문을 쿠팡에서 상품준비중 처리(미처리 시)한 뒤 "
                    + "송장번호를 등록한다 (등록되면 쿠팡이 배송지시로 전환, 이후 추적 자동). "
                    + "준비중까지만 성공하고 송장 등록이 실패한 경우 재호출하면 송장부터 재시도된다. "
                    + "오류: 401 unauthorized / 404 order_not_found / 409 invalid_state(이미 등록 등) / "
                    + "400 bad_request / 502 coupang_api_error.")
    @PostMapping("/orders/{orderId}/ship")
    public ResponseEntity<Object> ship(
            @Parameter(description = "내부 인증 토큰 (공통 app.internal.token 값(application-internal.yml))")
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Parameter(description = "쿠팡 주문번호") @PathVariable long orderId,
            @RequestBody ShipRequest request) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            return ResponseEntity.ok(fulfillmentService.ship(
                    orderId, request.deliveryCompanyCode(), request.invoiceNumber()));
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().startsWith("주문을 찾을 수 없습니다")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            String error = status == HttpStatus.NOT_FOUND ? "order_not_found" : "bad_request";
            return ResponseEntity.status(status).body(Map.of("error", error, "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "invalid_state", "message", e.getMessage() == null ? "" : e.getMessage()));
        } catch (CoupangApiException e) {
            log.error("판매자배송 출고 처리 실패 orderId={}", orderId, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "coupang_api_error",
                    "message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }
}
