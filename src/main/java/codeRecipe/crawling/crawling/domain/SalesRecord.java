package codeRecipe.crawling.crawling.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "sales_record")
public class SalesRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long recordId; // 판매 내역 고유 ID

    @ManyToOne
    @JoinColumn(name = "location_id", nullable = false)
    private SalesLocation salesLocation; // 판매처 (Foreign Key)

    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product; // 상품 (Foreign Key)

    @Column(nullable = false)
    private LocalDate salesDate; // 판매 날짜

    @Column(nullable = false)
    private Long quantity; // 매출 수량

    @Column
    private Long salesPrice; // 정가

    @Column
    private Long regionSalesAmount; // 지점별 판매 매출

    @Column
    private Long regionSalesQuantity; // 지점별 판매 수량

    @Column
    private Long salesAmount; // 매출 금액 (합계)

    @Column
    private Long actualSales; // 실매출 금액 (핫트랙스 사이트)

    @Column
    private Long salesCost; // 매출 원가 (핫트랙스 사이트)

    @Column
    private LocalDate createdAt;


    public boolean isSameSalesRecord(Long quantity, Long salesPrice, Long regionSalesAmount, Long regionSalesQuantity, Long salesAmount) {
        return this.quantity.equals(quantity) &&
                ((this.salesPrice == null && salesPrice == null) ||
                        (this.salesPrice != null && this.salesPrice.equals(salesPrice))) &&
                ((this.regionSalesAmount == null && regionSalesAmount == null) ||
                        (this.regionSalesAmount != null && this.regionSalesAmount.equals(regionSalesAmount))) &&
                ((this.regionSalesQuantity == null && regionSalesQuantity == null) ||
                        (this.regionSalesQuantity != null && this.regionSalesQuantity.equals(regionSalesQuantity))) &&
                ((this.salesAmount == null && salesAmount == null) ||
                        (this.salesAmount != null && this.salesAmount.equals(salesAmount)));
    }
}
