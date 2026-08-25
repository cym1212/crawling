package codeRecipe.crawling.crawling.hottracks;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 교보 발주 수동 실행 엔드포인트 (쿠팡 CoupangSyncController 스타일).
 * 스케줄러(수집 매시 40분 / 다이제스트 08:15·12:15)가 자동으로 하는 작업을 즉시 수동 실행한다.
 * 핫트랙스에 로그인해 크롤링하고 공유 테이블에 쓰므로 X-Internal-Token을 요구한다.
 * 예: curl -X POST -H "X-Internal-Token: {토큰}" localhost:8081/hottracks/orders/collect
 */
@Tag(name = "2. 교보 발주 수동 실행",
        description = "스케줄러(발주 수집 매시 40분, 다이제스트 08:15/12:15)가 자동으로 하는 작업을 "
                + "즉시 수동 실행하는 API. 모든 요청에 X-Internal-Token 헤더 필요.")
@RestController
@RequiredArgsConstructor
public class HottracksSyncController {

    private final HottracksOrderService orderService;
    private final HottracksInternalAuth internalAuth;

    @Operation(summary = "① 발주 수집 (알림 없음)",
            description = "핫트랙스에 로그인해 홈 발주 목록을 파싱하고 각 발주의 상세(상품·바코드·발주량)를 "
                    + "hottracks_purchase_order(+item) 테이블에 저장한다. 이미 저장된 발주는 건너뛴다(멱등). "
                    + "슬랙 발송은 하지 않는다. 스케줄러가 매시 40분에 자동 실행하는 작업과 동일. "
                    + "응답: 이번에 새로 저장된 발주 수.")
    @PostMapping("/hottracks/orders/collect")
    public ResponseEntity<Object> collect(
            @Parameter(description = "내부 인증 토큰 (application-hottracks.yml의 hottracks.internal.token 값)")
            @RequestHeader(value = "X-Internal-Token", required = false) String token) throws Exception {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        int collected = orderService.collectOnly();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", "교보 발주 수집 완료 - 신규 " + collected + "건 (이미 저장된 발주는 미포함)");
        body.put("collectedCount", collected);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "② 발주 수집 + 다이제스트",
            description = "발주를 수집한 뒤, 아직 알림 안 된 발주를 슬랙 다이제스트로 발송한다. 0건이면 발송하지 않는다. "
                    + "dryRun=true면 수집은 수행하되 슬랙 발송·알림 처리 없이 미리보기 텍스트만 반환. "
                    + "스케줄러가 08:15/12:15에 자동 실행하는 작업과 동일.")
    @PostMapping("/hottracks/orders/digest")
    public ResponseEntity<Object> digest(
            @Parameter(description = "내부 인증 토큰 (application-hottracks.yml의 hottracks.internal.token 값)")
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Parameter(description = "true면 슬랙 발송·알림 처리 없이 미리보기만 반환")
            @RequestParam(defaultValue = "false") boolean dryRun) throws Exception {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        int collected = orderService.collectOnly();
        return ResponseEntity.ok(orderService.sendDigest(dryRun, collected));
    }
}
