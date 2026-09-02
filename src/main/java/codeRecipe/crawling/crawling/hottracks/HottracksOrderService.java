package codeRecipe.crawling.crawling.hottracks;

import codeRecipe.crawling.crawling.domain.HottracksPurchaseOrder;
import codeRecipe.crawling.crawling.repository.HottracksPurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 교보 발주 수집 + 다이제스트(Slack) 발송.
 * 쿠팡 판매자배송 CoupangMarketplaceOrderService.runDigestJob 패턴을 따른다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HottracksOrderService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final HottracksOrderScriptExecutor orderScriptExecutor;
    private final HottracksPurchaseOrderRepository orderRepository;
    private final HottracksSlackNotifier slackNotifier;

    /** @param pendingCount 알림은 이미 갔지만 아직 납품 처리 안 된 발주 수(매일 재알림 대상) */
    public record DigestResult(int collectedCount, int unnotifiedCount, int notifiedCount, int pendingCount,
                               boolean slackSent, String preview) {
    }

    /** 수집만 수행(알림 없음). 매시 크론용. 반환: 새로 저장된 발주 수 */
    public int collectOnly() throws Exception {
        int collected = orderScriptExecutor.collectOrders();
        log.info("교보 발주 수집 완료(알림 없음) 수집={}", collected);
        return collected;
    }

    /** 스케줄 진입점: 발주 수집 후 다이제스트 발송 (정시 알림용) */
    public DigestResult runDigestJob() throws Exception {
        int collected = orderScriptExecutor.collectOrders();
        DigestResult result = sendDigest(false, collected);
        log.info("교보 발주 다이제스트 완료 수집={} 신규={} 미처리={} 발송={}",
                collected, result.unnotifiedCount(), result.pendingCount(), result.slackSent());
        return result;
    }

    /**
     * 발주 다이제스트 발송: 신규(미알림) 발주 + 미처리(알림은 갔지만 아직 납품 처리 안 됨) 발주.
     * 미처리 발주는 처리될 때까지 매일 재알림한다(사용자가 잊지 않도록). 처리 = bsight delivered_at 기록
     * (봇 납품확인 또는 홈 발주확정 목록에서 사라짐 감지).
     * 신규도 미처리도 없으면 발송하지 않는다.
     * @param dryRun true면 발송·알림처리 없이 미리보기만 반환
     */
    public DigestResult sendDigest(boolean dryRun, int collectedCount) {
        List<HottracksPurchaseOrder> unnotified = orderRepository.findByNotifiedAtIsNullOrderByDetectedAtAsc();

        // 미처리 재알림 대상: 납품 전(delivered_at null)인데 이미 알림이 간 것(신규와 중복 표시 방지). 발주일 오래된 순.
        List<HottracksPurchaseOrder> pending = new ArrayList<>();
        for (HottracksPurchaseOrder o : orderRepository.findByDeliveredAtIsNull()) {
            if (o.getNotifiedAt() != null) {
                pending.add(o);
            }
        }
        pending.sort((a, b) -> String.valueOf(a.getPlorDate()).compareTo(String.valueOf(b.getPlorDate())));

        if (unnotified.isEmpty() && pending.isEmpty()) {
            log.info("교보 발주 다이제스트 - 신규·미처리 발주 없음 (발송 생략)");
            return new DigestResult(collectedCount, 0, 0, 0, false, "신규·미처리 발주 없음");
        }

        String text = buildMessage(unnotified, pending);
        if (dryRun) {
            return new DigestResult(collectedCount, unnotified.size(), 0, pending.size(), false, text);
        }

        boolean sent = slackNotifier.send(text);

        // 실제로 발송된 경우에만 알림 완료(notified_at)로 표시한다.
        // 웹훅 미설정·Slack 오류로 못 보냈는데 표시해 버리면 그 발주는 설정을 고친 뒤에도 영영 알림이 안 간다
        // (2026-09-01 창원점 사례). 미발송이면 다음 다이제스트에서 다시 시도한다.
        if (!sent) {
            log.warn("교보 발주 다이제스트 미발송 — 신규 {}건은 알림 완료로 표시하지 않음(다음 다이제스트에 재시도)", unnotified.size());
            return new DigestResult(collectedCount, unnotified.size(), 0, pending.size(), false, text);
        }

        LocalDateTime notifiedAt = LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS);
        List<HottracksPurchaseOrder> toMark = new ArrayList<>();
        for (HottracksPurchaseOrder order : unnotified) {
            toMark.add(order.toBuilder()
                    .status("NOTIFIED".equals(order.getStatus()) ? order.getStatus() : "NOTIFIED")
                    .notifiedAt(notifiedAt)
                    .build());
        }
        orderRepository.saveAll(toMark);
        return new DigestResult(collectedCount, unnotified.size(), toMark.size(), pending.size(), sent, text);
    }

    private String buildMessage(List<HottracksPurchaseOrder> fresh, List<HottracksPurchaseOrder> pending) {
        LocalDate today = LocalDate.now(SEOUL);
        StringBuilder sb = new StringBuilder();
        sb.append("📦 교보 발주 알림 · ").append(todayLabel(today)).append("\n");
        sb.append("신규 ").append(fresh.size()).append("건 · 미처리 ").append(pending.size()).append("건\n");

        if (!fresh.isEmpty()) {
            sb.append("\n🆕 *신규 발주*\n");
            for (HottracksPurchaseOrder o : fresh) {
                sb.append("• ").append(orderLine(o)).append("\n");
            }
        }
        if (!pending.isEmpty()) {
            sb.append("\n⏳ *미처리 발주* (납품확인 전 — 처리될 때까지 매일 알립니다)\n");
            for (HottracksPurchaseOrder o : pending) {
                sb.append("• ").append(orderLine(o))
                        .append(" · ").append(elapsedLabel(o.getPlorDate(), today))
                        .append(" · ").append(stateLabel(o.getStatus()))
                        .append("\n");
            }
        }
        sb.append("\n30-we.com 교보 발주 납품 화면에서 거래명세서 등록 → 배송대기(납품확인)까지 처리해 주세요.");
        return sb.toString();
    }

    /** "지점 / 발주번호 N / YYYY-MM-DD / N개" */
    private String orderLine(HottracksPurchaseOrder o) {
        return (o.getPlorRdpName() == null ? o.getPlorRdpCode() : o.getPlorRdpName())
                + " / 발주번호 " + o.getPlorNum()
                + " / " + formatDate(o.getPlorDate())
                + " / " + (o.getSumPlorQntt() == null ? "?" : o.getSumPlorQntt()) + "개";
    }

    /** 발주일 기준 경과일 */
    private String elapsedLabel(String yyyymmdd, LocalDate today) {
        try {
            LocalDate d = LocalDate.parse(yyyymmdd, DateTimeFormatter.BASIC_ISO_DATE);
            long days = ChronoUnit.DAYS.between(d, today);
            return days <= 0 ? "오늘" : days + "일 경과";
        } catch (Exception e) {
            return "";
        }
    }

    /** bsight status → 사람이 읽는 상태 */
    private String stateLabel(String status) {
        if (status == null) return "";
        switch (status) {
            case "DELIVERED_TMP": return "임시저장됨(납품확인 대기)";
            case "DELIVERING":    return "봇 처리 중";
            case "FAILED":        return "봇 실패(재시도 필요)";
            default:              return "미처리";
        }
    }

    private String todayLabel(LocalDate d) {
        String[] days = {"월", "화", "수", "목", "금", "토", "일"};
        return d.getMonthValue() + "월 " + d.getDayOfMonth() + "일(" + days[d.getDayOfWeek().getValue() - 1] + ")";
    }

    private String formatDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) {
            return yyyymmdd == null ? "" : yyyymmdd;
        }
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }
}
