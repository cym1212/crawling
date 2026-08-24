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
 * 판매자배송(마켓플레이스) 주문. 다이제스트 알림 + 30-we.com 출고 화면 + 출고 처리 상태 추적용.
 * 주문번호(orderId) 기준 1행. 분리배송(한 주문 다중 배송박스)은 첫 박스만 저장한다 (희귀 케이스, 수집 시 경고 로그).
 * 개인정보 최소화: 쿠팡이 제공하는 마스킹된 이름·안심번호만 저장된다 (실번호는 쿠팡이 주지 않음).
 */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "coupang_marketplace_order",
        uniqueConstraints = @UniqueConstraint(name = "uk_marketplace_order_id", columnNames = "order_id"))
public class CoupangMarketplaceOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "marketplace_order_id")
    private Long marketplaceOrderId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "shipment_box_id")
    private Long shipmentBoxId;          // 준비중 처리·송장 등록에 필요

    @Column(name = "ordered_at")
    private LocalDateTime orderedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "coupang_status", length = 30)
    private String coupangStatus;        // ACCEPT → (준비중 처리 후) INSTRUCT → (송장 등록 후) DEPARTURE

    @Column(name = "orderer_name", length = 100)
    private String ordererName;          // 쿠팡이 마스킹해서 제공 (예: 신*희)

    @Column(name = "receiver_name", length = 100)
    private String receiverName;

    @Column(name = "receiver_phone", length = 50)
    private String receiverPhone;        // 안심번호

    @Column(name = "receiver_addr1", length = 300)
    private String receiverAddr1;

    @Column(name = "receiver_addr2", length = 300)
    private String receiverAddr2;

    @Column(name = "receiver_post_code", length = 20)
    private String receiverPostCode;

    @Column(name = "parcel_print_message", length = 300)
    private String parcelPrintMessage;   // 배송 요청사항

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    @Column(name = "total_price")
    private Long totalPrice;             // 결제금액 합

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;    // 다이제스트 발송 시각 (null = 미알림)

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt; // 쿠팡 상품준비중 처리 완료 시각

    @Column(name = "delivery_company_code", length = 30)
    private String deliveryCompanyCode;

    @Column(name = "tracking_number", length = 50)
    private String trackingNumber;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;     // 쿠팡 송장 등록 완료 시각

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;
}
