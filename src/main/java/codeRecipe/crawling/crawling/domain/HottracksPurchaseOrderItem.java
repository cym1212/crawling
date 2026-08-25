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
 * 교보 발주의 상품 항목. 30-we 거래명세서 생성(바코드 매칭)·납품확인 페이지 수량 입력에 사용.
 * prdt1012 납품확인 페이지의 상품 행에 1:1 대응.
 */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "hottracks_purchase_order_item",
        indexes = @Index(name = "ix_hottracks_poi_order", columnList = "purchase_order_id"))
public class HottracksPurchaseOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "purchase_order_id", nullable = false)
    private Long purchaseOrderId;        // HottracksPurchaseOrder FK

    @Column(name = "barcode", length = 50)
    private String barcode;              // 상품코드(바코드). 30-we TradeProduct 완전일치 매칭 키. 정규화 저장.

    @Column(name = "cmdt_id", length = 30)
    private String cmdtId;               // 핫트랙스 내부 상품ID. 납품확인 페이지 행 식별 보조.

    @Column(name = "product_name", length = 500)
    private String productName;

    @Column(name = "plor_qntt", nullable = false)
    private Integer plorQntt;            // 발주량

    @Column(name = "sale_unpr")
    private Long saleUnpr;               // 판매가(개당)

    @Column(name = "pros_qntt")
    private Integer prosQntt;            // 납품량 (명세서 확정 후 채움. 최초 null)

    @Column(name = "match_status", length = 20)
    private String matchStatus;          // MATCHED / UNMATCHED (30-we 매칭 결과 관측용)
}
