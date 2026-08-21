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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 쿠팡 로켓그로스 일별 판매 집계. 주문 API를 날짜 단위로 수집해 "결제일 × 옵션" 기준으로 합산한다.
 * (sales_date, vendor_item_id) 유니크 — 같은 날짜를 재수집해도 덮어써서 멱등.
 */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "coupang_sales",
        uniqueConstraints = @UniqueConstraint(name = "uk_sales_date_item", columnNames = {"sales_date", "vendor_item_id"}))
public class CoupangSales {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sales_id")
    private Long salesId;

    @Column(name = "sales_date", nullable = false)
    private LocalDate salesDate;          // 결제일 (Asia/Seoul 기준)

    @Column(name = "vendor_item_id", nullable = false)
    private Long vendorItemId;

    @Column(name = "product_name", length = 500)
    private String productName;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;             // 그날 판매 수량 합계

    @Column(name = "sales_amount", nullable = false)
    private Long salesAmount;             // 그날 매출액 합계 (수량 × 단가)

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;
}
