package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.domain.CoupangInventory;
import codeRecipe.crawling.crawling.domain.CoupangProduct;
import codeRecipe.crawling.crawling.repository.CoupangInventoryRepository;
import codeRecipe.crawling.crawling.repository.CoupangProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 로켓창고 재고 수집 → coupang_inventory 최신 스냅샷 upsert.
 * fetchAllInventory()는 실시간 내부 API(CoupangLiveInventoryService)에서도 재사용한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangInventorySyncService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long RG_PAGE_INTERVAL_MS = 1200; // 로켓창고 재고 API 분당 50회 제한
    private static final int MAX_PAGES = 200;

    private final CoupangApiClient coupangApiClient;
    private final CoupangInventoryRepository coupangInventoryRepository;
    private final CoupangProductRepository coupangProductRepository;

    public record InventoryItem(Long vendorItemId, String externalSkuId,
                                int orderableQuantity, Integer salesCountLast30Days) {
    }

    /** 로켓창고 재고 전체를 페이징으로 수집해 raw 리스트로 반환 (DB 접근 없음) */
    public List<InventoryItem> fetchAllInventory() {
        List<InventoryItem> items = new ArrayList<>();
        String nextToken = null;
        int pages = 0;
        Set<String> seenTokens = new HashSet<>();

        do {
            JsonNode response = coupangApiClient.getRocketGrowthInventorySummaries(nextToken);
            JsonNode data = response.path("data");
            if (!data.isArray()) {
                throw new CoupangApiException(
                        "쿠팡 재고 응답 형식 오류: " + response.path("message").asText(""), null, null);
            }
            for (JsonNode node : data) {
                JsonNode vidNode = node.path("vendorItemId");
                if (vidNode.isMissingNode() || vidNode.isNull()) {
                    continue;
                }
                items.add(new InventoryItem(
                        vidNode.asLong(),
                        CoupangJsonUtils.textOrNull(node, "externalSkuId"),
                        node.path("inventoryDetails").path("totalOrderableQuantity").asInt(0),
                        CoupangJsonUtils.intOrNull(node.path("salesCountMap"), "SALES_COUNT_LAST_THIRTY_DAYS")
                ));
            }
            nextToken = CoupangJsonUtils.textOrNull(response, "nextToken");
            if (nextToken != null) {
                if (!seenTokens.add(nextToken)) {
                    log.warn("쿠팡 재고 nextToken 반복 감지 - 페이징 중단 token={}", nextToken);
                    break;
                }
                sleep(RG_PAGE_INTERVAL_MS);
            }
            pages++;
        } while (nextToken != null && pages < MAX_PAGES);

        log.info("쿠팡 재고 수집 완료 pages={} items={}", pages, items.size());
        return items;
    }

    /**
     * 수집 → 상품명 매핑 → upsert. HTTP는 트랜잭션 밖에서 끝내고 saveAll만 저장 트랜잭션.
     * @return 이번 동기화로 저장된 재고 스냅샷 전체
     */
    public List<CoupangInventory> syncInventory() {
        List<InventoryItem> items = fetchAllInventory();

        Map<Long, String> nameByVendorItemId = new HashMap<>();
        for (CoupangProduct product : coupangProductRepository.findAll()) {
            nameByVendorItemId.put(product.getVendorItemId(), product.displayName());
        }
        Map<Long, CoupangInventory> existingByVendorItemId = new HashMap<>();
        for (CoupangInventory inventory : coupangInventoryRepository.findAll()) {
            existingByVendorItemId.put(inventory.getVendorItemId(), inventory);
        }

        LocalDateTime collectedAt = LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS);
        List<CoupangInventory> toSave = new ArrayList<>();
        for (InventoryItem item : items) {
            CoupangInventory existing = existingByVendorItemId.get(item.vendorItemId());
            String name = nameByVendorItemId.get(item.vendorItemId());
            if (existing == null) {
                toSave.add(CoupangInventory.builder()
                        .vendorItemId(item.vendorItemId())
                        .externalSkuId(item.externalSkuId())
                        .productName(name)
                        .orderableQuantity(item.orderableQuantity())
                        .salesCountLast30Days(item.salesCountLast30Days())
                        .collectedAt(collectedAt)
                        .build());
            } else {
                toSave.add(existing.toBuilder()
                        .externalSkuId(item.externalSkuId())
                        .productName(name != null ? name : existing.getProductName()) // 매핑 없으면 기존 이름 유지
                        .orderableQuantity(item.orderableQuantity())
                        .salesCountLast30Days(item.salesCountLast30Days())
                        .collectedAt(collectedAt)
                        .build());
            }
        }
        // 이번 수집에 없는 상품(판매 종료 등)은 삭제하지 않음 — 오래된 collected_at으로 자연 구분
        List<CoupangInventory> saved = coupangInventoryRepository.saveAll(toSave);
        log.info("쿠팡 재고 동기화 완료 saved={} collectedAt={}", saved.size(), collectedAt);
        return saved;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoupangApiException("쿠팡 재고 페이징 대기 중 인터럽트", null, e);
        }
    }
}
