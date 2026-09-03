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

    /** 출고 처리 요청 본문. shipmentBoxId는 박스가 하나뿐인 주문이면 생략 가능, 분할 주문이면 필수 */
    public record ShipRequest(String deliveryCompanyCode, String invoiceNumber, Long shipmentBoxId) {
    }

    /** 발주확인 요청 본문 */
    public record AcknowledgeRequest(java.util.List<Long> shipmentBoxIds) {
    }

    /** 일괄 출고 요청 본문 */
    public record BulkShipRequest(java.util.List<CoupangFulfillmentService.BulkShipItem> items) {
    }

    @Operation(summary = "판매자배송 일괄 출고 (여러 박스 준비중 + 송장 등록)",
            description = "30-we.com [쿠팡 일괄 등록] 버튼용. 여러 배송박스를 순차로 출고 처리한다 "
                    + "(박스별 준비중 처리 + 송장 등록 — 개별 /ship과 동일 로직). "
                    + "개별 건이 실패해도 나머지는 계속 진행하며 results에 박스별 성공/실패와 사유를 담아 반환한다. "
                    + "오류: 401 unauthorized / 400 bad_request(목록 비어있음).")
    @PostMapping("/orders/ship-bulk")
    public ResponseEntity<Object> shipBulk(
            @Parameter(description = "내부 인증 토큰 (공통 app.internal.token 값(application-internal.yml))")
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody BulkShipRequest request) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            return ResponseEntity.ok(fulfillmentService.shipBulk(request.items()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "bad_request", "message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    @Operation(summary = "판매자배송 발주확인 처리 (결제완료 → 상품준비중)",
            description = "30-we.com [발주확인 처리] 버튼용. 체크한 배송박스들을 일괄로 상품준비중으로 전환한다 "
                    + "(Wing의 발주확인과 동일 — 전환 후에는 구매자 단독 취소 불가). "
                    + "처리 직후 각 주문의 수취인 정보를 쿠팡에 재조회해서 결제완료 동안 바뀐 배송지를 갱신하고, "
                    + "갱신된 박스 목록을 receiverUpdated로 반환한다. 이미 처리·출고·취소된 박스는 건너뛴다(멱등). "
                    + "오류: 401 unauthorized / 400 bad_request / 502 coupang_api_error.")
    @PostMapping("/orders/acknowledge")
    public ResponseEntity<Object> acknowledge(
            @Parameter(description = "내부 인증 토큰 (공통 app.internal.token 값(application-internal.yml))")
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody AcknowledgeRequest request) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            return ResponseEntity.ok(fulfillmentService.acknowledge(request.shipmentBoxIds()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "bad_request", "message", e.getMessage() == null ? "" : e.getMessage()));
        } catch (CoupangApiException e) {
            log.error("판매자배송 발주확인 처리 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "coupang_api_error",
                    "message", e.getMessage() == null ? "" : e.getMessage()));
        }
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
            description = "30-we.com [쿠팡 등록] 버튼용. 해당 배송박스를 쿠팡에서 상품준비중 처리(미처리 시)한 뒤 "
                    + "송장번호를 등록한다 (등록되면 쿠팡이 배송지시로 전환, 이후 추적 자동). "
                    + "배송비 그룹이 달라 주문이 여러 박스로 나뉜 경우 body의 shipmentBoxId로 박스를 지정해야 하며 "
                    + "(박스 1개면 생략 가능), 박스마다 각각 호출한다. "
                    + "준비중까지만 성공하고 송장 등록이 실패한 경우 재호출하면 송장부터 재시도된다. "
                    + "오류: 401 unauthorized / 404 order_not_found / 409 invalid_state(이미 등록·취소 등) / "
                    + "400 bad_request(박스 미지정 포함) / 502 coupang_api_error.")
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
                    orderId, request.shipmentBoxId(), request.deliveryCompanyCode(), request.invoiceNumber()));
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
