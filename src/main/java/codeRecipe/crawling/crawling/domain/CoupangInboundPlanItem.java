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

/** 로켓그로스 입고 계획의 SKU별 항목 (의뢰 #8) */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "coupang_inbound_plan_item",
        indexes = @Index(name = "ix_inbound_item_plan", columnList = "plan_id"))
public class CoupangInboundPlanItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "plan_item_id")
    private Long planItemId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "vendor_item_id", nullable = false)
    private Long vendorItemId;               // 쿠팡 옵션(SKU) ID — 에이전트가 이 값으로 WING 검색

    @Column(name = "product_name", length = 500)
    private String productName;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;                // 신청 수량

    @Column(name = "accepted_quantity")
    private Integer acceptedQuantity;        // 입고 반영 수량 (추적 배치가 기록)

    @Column(name = "baseline_quantity")
    private Integer baselineQuantity;        // 제출 시점의 로켓창고 재고 (반영 추정 기준선, 내부용)
}
