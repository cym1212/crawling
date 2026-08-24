package codeRecipe.crawling.crawling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매자배송 주문의 상품 항목. 다이제스트 표시·송장 등록(옵션ID별 DTO 필요)·라벨 품목명에 사용.
 */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "coupang_marketplace_order_item",
        indexes = @Index(name = "ix_marketplace_item_order_id", columnList = "order_id"))
public class CoupangMarketplaceOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "vendor_item_id")
    private Long vendorItemId;

    @Column(name = "product_name", length = 500)
    private String productName;   // 노출상품명 (vendorItemName)

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price")
    private Long unitPrice;       // 개당 판매가
}
