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
 * 교보(핫트랙스 Partner Portal) 발주. 교보가 우리(비사이트)에게 발주 → 우리가 납품하는 구조.
 * 발주 다이제스트 알림 + 30-we.com 거래명세서 생성 + 납품확인 상태 추적용.
 *
 * <p>발주 1건 = (plorRdpCode, plorDate, plorNum) 복합키. 홈 gridPlor 한 행에 대응.
 * 쿠팡 판매자배송({@code CoupangMarketplaceOrder})과 동일한 상태-타임스탬프 패턴을 따른다.
 */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "hottracks_purchase_order",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_hottracks_plor",
                columnNames = {"plor_rdp_code", "plor_date", "plor_num"}))
public class HottracksPurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "purchase_order_id")
    private Long purchaseOrderId;

    // ===== 발주 식별키 (gridPlor 행) =====
    @Column(name = "plor_rdp_code", nullable = false, length = 20)
    private String plorRdpCode;          // 수불처(지점) 코드 (예: 527)

    @Column(name = "plor_date", nullable = false, length = 8)
    private String plorDate;             // 발주일 YYYYMMDD (예: 20260824)

    @Column(name = "plor_num", nullable = false, length = 20)
    private String plorNum;              // 발주번호 (예: 00022)

    // ===== 발주 헤더 정보 =====
    @Column(name = "plor_rdp_name", length = 100)
    private String plorRdpName;          // 지점명 (예: 목동점)

    @Column(name = "vndr_code", length = 20)
    private String vndrCode;             // 협력사 코드 (우리 = 0817671)

    @Column(name = "plor_prgs_cdtn_code", length = 10)
    private String plorPrgsCdtnCode;     // 발주진행상태 코드 (예: 502)

    @Column(name = "plor_prgs_cdtn_name", length = 50)
    private String plorPrgsCdtnName;     // 발주진행상태명 (예: 발주확정)

    @Column(name = "sum_plor_qntt")
    private Integer sumPlorQntt;         // 발주 총수량

    @Column(name = "plor_decr_id", length = 30)
    private String plorDecrId;           // 발주 담당자 ID (예: KB12093)

    // ===== 라이프사이클 상태 =====
    // NEW → NOTIFIED → SUPPLY_CREATED → DELIVERING → DELIVERED_TMP/DELIVERED → FAILED
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    // ===== 상태 타임스탬프 (쿠팡 패턴) =====
    @Column(name = "detected_at")
    private LocalDateTime detectedAt;    // 발주 감지·저장 시각

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;    // Slack 다이제스트 발송 시각 (null = 미알림)

    @Column(name = "supply_linked_at")
    private LocalDateTime supplyLinkedAt; // 30-we 거래명세서 생성 시각 (관측용)

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;   // 핫트랙스 납품확인(③) 완료 시각

    @Column(name = "delivery_mode", length = 10)
    private String deliveryMode;         // TMPR(임시저장) / CMPLT(납품확인)

    @Column(name = "last_error", length = 1000)
    private String lastError;            // 납품등록 봇 실패 메시지

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;   // 마지막 수집 시각

    // ===== 발주 원본 엑셀([엑셀출력]) 보관 =====
    @Column(name = "excel_path", length = 500)
    private String excelPath;            // 발주 원본 엑셀 저장 경로 (null = 미저장)

    @Column(name = "excel_saved_at")
    private LocalDateTime excelSavedAt;  // 엑셀 저장 시각

    /** 기수집 발주에 엑셀을 소급 저장할 때 사용 (@Builder toBuilder 대신 부분 갱신용) */
    public void setExcelPath(String excelPath) {
        this.excelPath = excelPath;
    }

    public void setExcelSavedAt(LocalDateTime excelSavedAt) {
        this.excelSavedAt = excelSavedAt;
    }
}
