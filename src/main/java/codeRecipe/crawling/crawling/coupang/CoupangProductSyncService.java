package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.domain.CoupangProduct;
import codeRecipe.crawling.crawling.repository.CoupangProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * vendorItemId → 상품명/옵션명 매핑 동기화.
 * 상품 목록 API(sellerProductId, sellerProductName)를 페이징하고,
 * 매핑이 없는 상품만 상세 API를 호출해 items[].vendorItemId/itemName을 수집한다.
 * 첫 실행은 상품당 상세 1콜(스로틀 250ms), 이후엔 목록 페이징만 돈다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangProductSyncService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_PAGES = 200;

    private final CoupangApiClient coupangApiClient;
    private final CoupangProductRepository coupangProductRepository;

    /** @param forceRefresh true면 매핑이 이미 있는 상품도 상세 조회를 다시 수행 */
    public int syncProducts(boolean forceRefresh) {
        List<CoupangProduct> all = coupangProductRepository.findAll();
        Map<Long, List<CoupangProduct>> bySellerProductId = all.stream()
                .filter(p -> p.getSellerProductId() != null)
                .collect(Collectors.groupingBy(CoupangProduct::getSellerProductId));
        Map<Long, CoupangProduct> byVendorItemId = all.stream()
                .collect(Collectors.toMap(CoupangProduct::getVendorItemId, Function.identity()));

        LocalDateTime now = LocalDateTime.now(SEOUL);
        Map<Long, CoupangProduct> toSave = new LinkedHashMap<>(); // vendorItemId 기준 중복 방지
        int pages = 0;
        int detailCalls = 0;
        String nextToken = null;
        Set<String> seenTokens = new HashSet<>();

        do {
            JsonNode response = coupangApiClient.getSellerProducts(nextToken);
            for (JsonNode productNode : response.path("data")) {
                JsonNode spidNode = productNode.path("sellerProductId");
                if (spidNode.isMissingNode() || spidNode.isNull()) {
                    continue;
                }
                long sellerProductId = spidNode.asLong();
                String listName = CoupangJsonUtils.textOrNull(productNode, "sellerProductName");

                List<CoupangProduct> existingRows = bySellerProductId.get(sellerProductId);
                if (existingRows == null || forceRefresh) {
                    JsonNode detail = coupangApiClient.getSellerProduct(sellerProductId);
                    detailCalls++;
                    for (JsonNode itemNode : detail.path("data").path("items")) {
                        JsonNode vidNode = itemNode.path("vendorItemId");
                        if (vidNode.isMissingNode() || vidNode.isNull()) {
                            continue; // 승인 전 상품은 vendorItemId가 null
                        }
                        long vendorItemId = vidNode.asLong();
                        String itemName = CoupangJsonUtils.textOrNull(itemNode, "itemName");
                        CoupangProduct existing = byVendorItemId.get(vendorItemId);
                        CoupangProduct merged = existing == null
                                ? CoupangProduct.builder()
                                        .vendorItemId(vendorItemId)
                                        .sellerProductId(sellerProductId)
                                        .productName(listName)
                                        .itemName(itemName)
                                        .updatedAt(now)
                                        .build()
                                : existing.toBuilder()
                                        .sellerProductId(sellerProductId)
                                        .productName(listName)
                                        .itemName(itemName)
                                        .updatedAt(now)
                                        .build();
                        toSave.put(vendorItemId, merged);
                    }
                } else {
                    // 이미 매핑된 상품: 상품명이 바뀐 경우만 상세 호출 없이 갱신 (vendorItemId는 불변)
                    String currentName = existingRows.get(0).getProductName();
                    if (listName != null && !listName.equals(currentName)) {
                        for (CoupangProduct row : existingRows) {
                            toSave.put(row.getVendorItemId(), row.toBuilder()
                                    .productName(listName)
                                    .updatedAt(now)
                                    .build());
                        }
                    }
                }
            }
            nextToken = CoupangJsonUtils.textOrNull(response, "nextToken");
            if (nextToken != null && !seenTokens.add(nextToken)) {
                log.warn("쿠팡 상품 목록 nextToken 반복 감지 - 페이징 중단 token={}", nextToken);
                break;
            }
            pages++;
        } while (nextToken != null && pages < MAX_PAGES);

        coupangProductRepository.saveAll(toSave.values());
        log.info("쿠팡 상품 매핑 동기화 완료 pages={} detailCalls={} saved={}", pages, detailCalls, toSave.size());
        return toSave.size();
    }
}
