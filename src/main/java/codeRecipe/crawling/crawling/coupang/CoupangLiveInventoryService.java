package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.coupang.dto.InternalInventoryResponse;
import codeRecipe.crawling.crawling.domain.CoupangInventory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 실시간 재고 조회 (내부 API용). 쿠팡을 라이브로 페이징 조회해 즉시 응답하고,
 * 조회 결과는 부수 효과로 coupang_inventory 스냅샷에도 반영한다 (저장 실패는 응답에 영향 없음).
 * synchronized + 60초 TTL 캐시 = single-flight: 동시 요청이 몰려도 쿠팡 호출은 1회.
 * 실패는 캐시하지 않으므로 다음 요청에서 재시도된다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangLiveInventoryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long CACHE_TTL_MS = 60_000;

    private final CoupangInventorySyncService inventorySyncService;

    private record Cached(long fetchedAtMillis, InternalInventoryResponse response) {
    }

    private Cached cache; // synchronized getInventory()에서만 접근

    public synchronized InternalInventoryResponse getInventory() {
        if (cache != null && System.currentTimeMillis() - cache.fetchedAtMillis() < CACHE_TTL_MS) {
            log.debug("실시간 재고 캐시 사용");
            return cache.response();
        }
        // 쿠팡 라이브 조회 + 상품명 매핑 (정기 동기화와 동일 경로)
        List<CoupangInventory> snapshot = inventorySyncService.prepareSnapshot();

        // DB 반영은 부수 효과 — 실패해도 실시간 응답은 정상 반환
        try {
            inventorySyncService.saveSnapshot(snapshot);
        } catch (Exception e) {
            log.warn("실시간 재고 조회 결과 DB 반영 실패 (응답은 정상 반환)", e);
        }

        List<InternalInventoryResponse.Item> responseItems = new ArrayList<>();
        for (CoupangInventory inventory : snapshot) {
            responseItems.add(new InternalInventoryResponse.Item(
                    inventory.getVendorItemId(),
                    inventory.getExternalSkuId(),
                    inventory.getProductName(),
                    inventory.getOrderableQuantity(),
                    inventory.getSalesCountLast30Days()));
        }
        LocalDateTime collectedAt = snapshot.isEmpty()
                ? LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS)
                : snapshot.get(0).getCollectedAt();
        InternalInventoryResponse fresh = new InternalInventoryResponse(collectedAt, responseItems);
        cache = new Cached(System.currentTimeMillis(), fresh);
        return fresh;
    }
}
