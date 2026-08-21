package codeRecipe.crawling.crawling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 쿠팡 로켓그로스(로켓창고) 재고 최신 스냅샷. 옵션(vendorItemId)당 1행 upsert.
 * 스키마는 docs/30we-기능개발-의뢰서.md 의뢰 #1과 일치해야 한다.
 */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "coupang_inventory",
        uniqueConstraints = @UniqueConstraint(name = "uk_vendor_item_id", columnNames = "vendor_item_id"))
public class CoupangInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inventory_id")
    private Long inventoryId;

    @Column(name = "vendor_item_id", nullable = false)
    private Long vendorItemId;

    @Column(name = "external_sku_id", length = 100)
    private String externalSkuId;

    @Column(name = "product_name", length = 500)
    private String productName;

    @Column(name = "orderable_quantity", nullable = false)
    private Integer orderableQuantity;

    // 기본 네이밍 전략은 sales_count_last30_days로 만들므로 반드시 명시
    @Column(name = "sales_count_last_30_days")
    private Integer salesCountLast30Days;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;
}
