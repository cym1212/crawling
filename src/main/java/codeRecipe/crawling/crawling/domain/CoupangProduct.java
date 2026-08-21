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
 * vendorItemId → 상품명/옵션명 매핑 (내부용 테이블).
 * 재고 API 응답에는 상품명이 없어서 상품 목록/상세 API로 별도 수집한다.
 */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "coupang_product",
        uniqueConstraints = @UniqueConstraint(name = "uk_coupang_product_vendor_item", columnNames = "vendor_item_id"))
public class CoupangProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coupang_product_id")
    private Long coupangProductId;

    @Column(name = "vendor_item_id", nullable = false)
    private Long vendorItemId;

    @Column(name = "seller_product_id")
    private Long sellerProductId;

    @Column(name = "product_name", length = 500)
    private String productName;   // 상품 목록 API의 sellerProductName

    @Column(name = "item_name", length = 500)
    private String itemName;      // 상품 상세 API의 옵션명

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 화면/알림 표기용 이름: 상품명 - 옵션명 */
    public String displayName() {
        if (productName == null) {
            return itemName;
        }
        if (itemName == null || itemName.isBlank()) {
            return productName;
        }
        return productName + " - " + itemName;
    }
}
