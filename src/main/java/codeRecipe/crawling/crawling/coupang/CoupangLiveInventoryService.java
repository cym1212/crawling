package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.coupang.dto.InternalInventoryResponse;
import codeRecipe.crawling.crawling.domain.CoupangProduct;
import codeRecipe.crawling.crawling.repository.CoupangProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 실시간 재고 조회 (내부 API용). 쿠팡을 라이브로 페이징 조회하며 DB 스냅샷(coupang_inventory)은 읽지 않는다.
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
    private final CoupangProductRepository coupangProductRepository;

    private record Cached(long fetchedAtMillis, InternalInventoryResponse response) {
    }

    private Cached cache; // synchronized getInventory()에서만 접근

    public synchronized InternalInventoryResponse getInventory() {
        if (cache != null && System.currentTimeMillis() - cache.fetchedAtMillis() < CACHE_TTL_MS) {
            log.debug("실시간 재고 캐시 사용");
            return cache.response();
        }
        List<CoupangInventorySyncService.InventoryItem> items = inventorySyncService.fetchAllInventory();

        Map<Long, String> nameByVendorItemId = new HashMap<>();
        for (CoupangProduct product : coupangProductRepository.findAll()) {
            nameByVendorItemId.put(product.getVendorItemId(), product.displayName());
        }

        List<InternalInventoryResponse.Item> responseItems = new ArrayList<>();
        for (CoupangInventorySyncService.InventoryItem item : items) {
            responseItems.add(new InternalInventoryResponse.Item(
                    item.vendorItemId(),
                    item.externalSkuId(),
                    nameByVendorItemId.get(item.vendorItemId()),
                    item.orderableQuantity(),
                    item.salesCountLast30Days()));
        }
        InternalInventoryResponse fresh = new InternalInventoryResponse(
                LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS), responseItems);
        cache = new Cached(System.currentTimeMillis(), fresh);
        return fresh;
    }
}
