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

/** 로켓그로스 입고 박스별 로젠 송장 (의뢰 #8). 30-we가 발급해 전달하면 에이전트가 WING에 등록한다. */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "coupang_inbound_invoice",
        uniqueConstraints = @UniqueConstraint(name = "uk_inbound_invoice_box", columnNames = {"plan_id", "box_no"}))
public class CoupangInboundInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "box_no", nullable = false)
    private Integer boxNo;                   // 1..box_count

    @Column(name = "tracking_number", nullable = false, length = 50)
    private String trackingNumber;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;          // 30-we가 전달한 시각

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;      // 에이전트가 WING 등록 완료한 시각
}
