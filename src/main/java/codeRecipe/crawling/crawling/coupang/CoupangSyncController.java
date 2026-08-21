package codeRecipe.crawling.crawling.coupang;

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

/**
 * 수동 트리거 엔드포인트 (CrawlingController 스타일).
 * 쿠팡 API 쿼터를 소모하고 공유 테이블에 쓰기 때문에 X-Internal-Token을 요구한다.
 * 예: curl -X POST -H "X-Internal-Token: {토큰}" localhost:8081/coupang/sync/inventory
 */
@Tag(name = "1. 쿠팡 수동 실행",
        description = "스케줄러(상품 매핑 매일 06:00, 재고 매시 10분, 부족 재고 매일 07:00)가 자동으로 하는 작업을 "
                + "즉시 수동 실행하는 API. 모든 요청에 X-Internal-Token 헤더 필요.")
@RestController
@RequiredArgsConstructor
public class CoupangSyncController {

    private final CoupangProductSyncService productSyncService;
    private final CoupangInventorySyncService inventorySyncService;
    private final CoupangRestockService restockService;
    private final CoupangInternalAuth internalAuth;

    @Operation(summary = "① 상품명 매핑 동기화",
            description = "쿠팡 상품 목록/상세 API를 호출해 옵션ID(vendorItemId) ↔ 상품명/옵션명 매핑을 "
                    + "coupang_product 테이블에 저장한다. 재고 API 응답에는 상품명이 없어서 이 매핑이 필요하다. "
                    + "⏱️ 첫 실행은 상품 수만큼 상세 API를 호출하므로 수십 초~수 분 소요. "
                    + "이후 실행은 신규/이름변경 상품만 처리해서 빠르다. "
                    + "forceRefresh=true면 전체 상품을 다시 조회한다(옵션명 변경까지 반영).")
    @PostMapping("/coupang/sync/products")
    public ResponseEntity<String> syncProducts(
            @Parameter(description = "내부 인증 토큰 (application-coupang.yml의 coupang.internal.token 값)")
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Parameter(description = "true면 이미 매핑된 상품도 상세 조회를 다시 수행")
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("unauthorized");
        }
        int saved = productSyncService.syncProducts(forceRefresh);
        return ResponseEntity.ok("쿠팡 상품 매핑 동기화 완료: " + saved + "건");
    }

    @Operation(summary = "② 로켓창고 재고 동기화",
            description = "쿠팡 로켓창고 재고 API를 전체 페이징 조회해서 옵션(SKU)별 주문가능수량과 "
                    + "최근 30일 판매량을 coupang_inventory 테이블에 최신 스냅샷으로 저장(upsert)한다. "
                    + "①에서 수집한 매핑으로 상품명을 함께 채운다 (매핑 전이면 상품명 NULL). "
                    + "30-we.com 재고 현황 화면이 이 테이블을 읽는다.")
    @PostMapping("/coupang/sync/inventory")
    public ResponseEntity<String> syncInventory(
            @Parameter(description = "내부 인증 토큰 (application-coupang.yml의 coupang.internal.token 값)")
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("unauthorized");
        }
        int saved = inventorySyncService.syncInventory();
        return ResponseEntity.ok("쿠팡 재고 동기화 완료: " + saved + "건");
    }

    @Operation(summary = "③ 부족 재고 계산 + 슬랙 알림",
            description = "②의 최신 재고 스냅샷을 기준으로 상품별 재고 소진 예상일을 계산한다 "
                    + "(일평균 판매 = 최근 30일 판매량 ÷ 30). 소진 예상이 임계일수(기본 7일) 이내면 "
                    + "목표일수(기본 21일)치를 채우는 입고 제안을 coupang_restock_suggestion 테이블에 생성하고 "
                    + "슬랙으로 알림을 보낸다. 같은 상품은 하루 1회만 제안. 기준값은 yml(coupang.restock.*)로 조정 가능.")
    @PostMapping("/coupang/restock")
    public ResponseEntity<String> restock(
            @Parameter(description = "내부 인증 토큰 (application-coupang.yml의 coupang.internal.token 값)")
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("unauthorized");
        }
        return ResponseEntity.ok(restockService.generateAndNotify());
    }
}
