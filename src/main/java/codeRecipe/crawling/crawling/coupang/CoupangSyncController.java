package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.domain.CoupangInventory;
import codeRecipe.crawling.crawling.domain.CoupangProduct;
import codeRecipe.crawling.crawling.domain.CoupangSales;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CoupangProductSyncService productSyncService;
    private final CoupangInventorySyncService inventorySyncService;
    private final CoupangRestockService restockService;
    private final CoupangOrderSyncService orderSyncService;
    private final CoupangMarketplaceOrderService marketplaceOrderService;
    private final CoupangRestockProperties restockProperties;
    private final CoupangInternalAuth internalAuth;

    @Operation(summary = "⑤ 판매자배송 주문 수집 + 다이제스트",
            description = "최근 24시간의 결제완료(ACCEPT) 판매자배송 주문을 수집해 저장하고(주문·상품·수취인), "
                    + "아직 알림 안 된 주문을 다이제스트 카드로 슬랙 발송한다. 0건이면 발송하지 않는다. "
                    + "dryRun=true면 수집은 수행하되 슬랙 발송·알림 처리 없이 미리보기만 반환. "
                    + "스케줄러가 매일 09:00/12:00에 자동 실행하는 작업과 동일.")
    @PostMapping("/coupang/orders/digest")
    public ResponseEntity<Object> orderDigest(
            @Parameter(description = "내부 인증 토큰 (application-coupang.yml의 coupang.internal.token 값)")
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Parameter(description = "true면 슬랙 발송·알림 처리 없이 미리보기만 반환")
            @RequestParam(defaultValue = "false") boolean dryRun) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        int collected = marketplaceOrderService.collectNewOrders();
        return ResponseEntity.ok(marketplaceOrderService.sendDigest(dryRun, collected));
    }

    @Operation(summary = "① 상품명 매핑 동기화",
            description = "쿠팡 상품 목록/상세 API를 호출해 계정의 전체 상품(카테고리 무관)의 "
                    + "옵션ID(vendorItemId) ↔ 상품명/옵션명 매핑을 coupang_product 테이블에 저장한다. "
                    + "재고 API 응답에는 상품명이 없어서 이 매핑이 필요하다. "
                    + "⏱️ 첫 실행은 상품 수만큼 상세 API를 호출하므로 수십 초~수 분 소요. "
                    + "이후 실행은 신규/이름변경 상품만 처리해서 빠르다. "
                    + "응답: 이번에 신규/갱신된 매핑 상세 목록. forceRefresh=true면 전체 상품을 다시 조회한다.")
    @PostMapping("/coupang/sync/products")
    public ResponseEntity<Object> syncProducts(
            @Parameter(description = "내부 인증 토큰 (application-coupang.yml의 coupang.internal.token 값)")
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Parameter(description = "true면 이미 매핑된 상품도 상세 조회를 다시 수행")
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        List<CoupangProduct> saved = productSyncService.syncProducts(forceRefresh);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", "상품 매핑 동기화 완료 - 신규/갱신 " + saved.size() + "건 (변경 없는 기존 매핑은 미포함)");
        body.put("savedCount", saved.size());
        body.put("items", saved);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "② 로켓그로스 재고 동기화",
            description = "쿠팡 로켓그로스 재고 API를 전체 페이징 조회해서 계정의 전체 옵션(SKU)별 주문가능수량과 "
                    + "최근 30일 판매량을 coupang_inventory 테이블에 최신 스냅샷으로 저장(upsert)한다. "
                    + "①에서 수집한 매핑으로 상품명을 함께 채운다 (매핑 전이면 상품명 NULL). "
                    + "30-we.com 재고 현황 화면이 이 테이블을 읽는다. 응답: 저장된 재고 스냅샷 전체 목록.")
    @PostMapping("/coupang/sync/inventory")
    public ResponseEntity<Object> syncInventory(
            @Parameter(description = "내부 인증 토큰 (application-coupang.yml의 coupang.internal.token 값)")
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        List<CoupangInventory> saved = inventorySyncService.syncInventory();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", "재고 동기화 완료 - " + saved.size() + "건");
        body.put("savedCount", saved.size());
        body.put("collectedAt", saved.isEmpty() ? null : saved.get(0).getCollectedAt());
        body.put("items", saved);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "④ 판매(주문) 수집",
            description = "쿠팡 주문 API를 하루 단위로 조회해서 '결제일 × 옵션'별 판매 수량/매출액을 "
                    + "coupang_sales 테이블에 집계 저장한다. 같은 날짜를 재수집하면 덮어쓰므로(멱등) 안전하다. "
                    + "쿠팡은 과거 30일까지만 제공하므로 배포 직후 30일 백필 1회 권장 "
                    + "(예: startDate=30일 전, endDate=오늘). 파라미터 생략 시 어제~오늘 수집. "
                    + "스케줄러는 매일 05:30에 최근 3일 창을 자동 수집한다. "
                    + "응답: 저장된 일별 집계 행 목록. 주문에서 발견된 미매핑 상품명은 coupang_product에도 보강된다.")
    @PostMapping("/coupang/sync/orders")
    public ResponseEntity<Object> syncOrders(
            @Parameter(description = "내부 인증 토큰 (application-coupang.yml의 coupang.internal.token 값)")
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Parameter(description = "수집 시작일 (yyyy-MM-dd, 생략 시 어제)")
            @RequestParam(required = false) String startDate,
            @Parameter(description = "수집 종료일 (yyyy-MM-dd, 생략 시 오늘)")
            @RequestParam(required = false) String endDate) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        LocalDate from = startDate != null ? LocalDate.parse(startDate) : LocalDate.now(SEOUL).minusDays(1);
        LocalDate to = endDate != null ? LocalDate.parse(endDate) : LocalDate.now(SEOUL);
        List<CoupangSales> saved = orderSyncService.syncOrders(from, to);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", "판매 수집 완료 (" + from + " ~ " + to + ") - 일별 집계 " + saved.size() + "행");
        body.put("savedCount", saved.size());
        body.put("items", saved);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "③ 부족 재고 계산 + 슬랙 알림",
            description = "②의 최신 재고 스냅샷과 ④의 일별 판매 이력으로 상품별 재고 소진 예상일을 계산한다(v2). "
                    + "일평균 = 7일 속도와 30일 속도 중 빠른 쪽 (신상품은 실제 판매 경과일 보정, "
                    + "일별 데이터 없는 상품은 쿠팡 30일 집계로 폴백). 소진 예상이 임계일수(기본 7일) 이내면 "
                    + "목표일수(기본 21일)치를 채우는 입고 제안을 생성하고 슬랙으로 알림(급증 상품 🔥 표시). "
                    + "같은 상품은 하루 1회만 제안. 기준값은 yml(coupang.restock.*)로 조정 가능. "
                    + "dryRun=true면 저장/슬랙 없이 계산 결과만 반환하며, 이때만 thresholdDays/targetDays로 "
                    + "기준을 바꿔 실험할 수 있다.")
    @PostMapping("/coupang/restock")
    public ResponseEntity<Object> restock(
            @Parameter(description = "내부 인증 토큰 (application-coupang.yml의 coupang.internal.token 값)")
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Parameter(description = "true면 저장/슬랙 없이 계산 결과만 반환 (테스트용)")
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Parameter(description = "[dryRun 전용] 임계일수 오버라이드 (기본: 설정값)")
            @RequestParam(required = false) Integer thresholdDays,
            @Parameter(description = "[dryRun 전용] 목표일수 오버라이드 (기본: 설정값)")
            @RequestParam(required = false) Integer targetDays) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        if (dryRun) {
            int threshold = thresholdDays != null ? thresholdDays : restockProperties.getThresholdDays();
            int target = targetDays != null ? targetDays : restockProperties.getTargetDays();
            List<CoupangRestockService.SimulatedSuggestion> simulated = restockService.simulate(threshold, target);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("result", String.format("드라이런 (임계 %d일 / 목표 %d일) - 제안 대상 %d건 (저장/슬랙 없음)",
                    threshold, target, simulated.size()));
            body.put("thresholdDays", threshold);
            body.put("targetDays", target);
            body.put("items", simulated);
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.ok(restockService.generateAndNotify());
    }
}
