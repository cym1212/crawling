package codeRecipe.crawling.crawling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 쿠팡 로켓그로스 부족 재고 입고 제안. 일 1회 생성, (vendorItemId, suggestionDate) 유니크.
 * 스키마는 docs/30we-기능개발-의뢰서.md 의뢰 #2와 일치해야 한다.
 */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "coupang_restock_suggestion",
        uniqueConstraints = @UniqueConstraint(name = "uk_item_date", columnNames = {"vendor_item_id", "suggestion_date"}))
public class CoupangRestockSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "suggestion_id")
    private Long suggestionId;

    @Column(name = "vendor_item_id", nullable = false)
    private Long vendorItemId;

    @Column(name = "product_name", length = 500)
    private String productName;

    @Column(name = "current_quantity", nullable = false)
    private Integer currentQuantity;

    @Column(name = "daily_avg_sales", nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyAvgSales;

    @Column(name = "days_until_stockout", precision = 10, scale = 1)
    private BigDecimal daysUntilStockout;

    @Column(name = "expected_stockout_date")
    private LocalDate expectedStockoutDate;

    @Column(name = "suggested_quantity", nullable = false)
    private Integer suggestedQuantity;

    // columnDefinition으로 varchar 고정 — Hibernate 6는 기본으로 MySQL 네이티브 ENUM 타입을 생성함
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(20)")
    private RestockStatus status;

    @Column(name = "suggestion_date", nullable = false)
    private LocalDate suggestionDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
