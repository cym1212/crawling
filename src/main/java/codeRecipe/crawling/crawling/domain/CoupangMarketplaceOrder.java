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
 * 판매자배송(마켓플레이스) 신규 주문 알림 기록 (중복 발송 방지용, 내부 테이블).
 * 알림을 보낸 주문번호를 저장해두고, 폴링 시 이미 기록된 주문은 건너뛴다.
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

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "order_summary", length = 1000)
    private String orderSummary;

    @Column(name = "notified_at", nullable = false)
    private LocalDateTime notifiedAt;
}
