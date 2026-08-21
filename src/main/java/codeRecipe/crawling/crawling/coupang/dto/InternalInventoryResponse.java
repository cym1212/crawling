package codeRecipe.crawling.crawling.coupang.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 내부 실시간 재고 API 응답. 형식은 docs/30we-기능개발-의뢰서.md §1-6 계약과 일치해야 한다.
 * collectedAt은 초 단위 절삭 → "yyyy-MM-dd'T'HH:mm:ss"로 직렬화된다.
 */
public record InternalInventoryResponse(LocalDateTime collectedAt, List<Item> items) {

    public record Item(Long vendorItemId, String externalSkuId, String productName,
                       int orderableQuantity, Integer salesCountLast30Days) {
    }
}
