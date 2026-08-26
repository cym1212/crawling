package codeRecipe.crawling.crawling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 로켓그로스 입고 계획 (의뢰 #8).
 * 30-we 신청 → RPA 에이전트가 WING 제출 → 송장 등록 → 입고 반영 추적의 전 과정을 이 행의 status로 관리한다.
 */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "coupang_inbound_plan")
public class CoupangInboundPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "plan_id")
    private Long planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(30)")
    private InboundPlanStatus status;

    @Column(name = "box_count", nullable = false)
    private Integer boxCount;

    @Column(name = "requested_by", length = 100)
    private String requestedBy;              // 신청자 (30-we 로그인 계정명)

    @Column(name = "wing_inbound_id", length = 30)
    private String wingInboundId;            // WING 입고 ID (제출 후 에이전트가 기록)

    @Column(name = "fulfillment_center", length = 50)
    private String fulfillmentCenter;        // 쿠팡이 배정한 물류센터 (예: 창원1)

    @Column(name = "arrival_date")
    private LocalDate arrivalDate;           // 도착예정일

    @Column(name = "barcode_pdf_ready", nullable = false, columnDefinition = "tinyint(1)")
    private boolean barcodePdfReady;         // 상품 바코드 PDF 회수됨

    @Column(name = "attach_pdf_ready", nullable = false, columnDefinition = "tinyint(1)")
    private boolean attachPdfReady;          // 물류 부착문서 PDF 회수됨

    @Column(name = "barcode_pdf_path", length = 500)
    private String barcodePdfPath;           // 서버 보관 경로 (crawling 내부용)

    @Column(name = "attach_pdf_path", length = 500)
    private String attachPdfPath;

    @Column(name = "fail_reason", length = 1000)
    private String failReason;               // FAILED 시 사유 (수동 폴백 안내용)

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;       // WING 제출 완료 시각

    @Column(name = "completed_at")
    private LocalDateTime completedAt;       // 입고 반영 확인 시각

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
