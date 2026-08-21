package codeRecipe.crawling.crawling.coupang;

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
@RestController
@RequiredArgsConstructor
public class CoupangSyncController {

    private final CoupangProductSyncService productSyncService;
    private final CoupangInventorySyncService inventorySyncService;
    private final CoupangRestockService restockService;
    private final CoupangInternalAuth internalAuth;

    @PostMapping("/coupang/sync/products")
    public ResponseEntity<String> syncProducts(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("unauthorized");
        }
        int saved = productSyncService.syncProducts(forceRefresh);
        return ResponseEntity.ok("쿠팡 상품 매핑 동기화 완료: " + saved + "건");
    }

    @PostMapping("/coupang/sync/inventory")
    public ResponseEntity<String> syncInventory(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("unauthorized");
        }
        int saved = inventorySyncService.syncInventory();
        return ResponseEntity.ok("쿠팡 재고 동기화 완료: " + saved + "건");
    }

    @PostMapping("/coupang/restock")
    public ResponseEntity<String> restock(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("unauthorized");
        }
        return ResponseEntity.ok(restockService.generateAndNotify());
    }
}
