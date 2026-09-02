package codeRecipe.crawling.crawling.hottracks;

import codeRecipe.crawling.crawling.domain.HottracksPurchaseOrder;
import codeRecipe.crawling.crawling.repository.HottracksPurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
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

    public record DigestResult(int collectedCount, int unnotifiedCount, int notifiedCount,
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
        log.info("교보 발주 다이제스트 완료 수집={} 발송대상={} 발송={}",
                collected, result.unnotifiedCount(), result.slackSent());
        return result;
    }

    /**
     * 미알림 발주 다이제스트 발송.
     * @param dryRun true면 발송·알림처리 없이 미리보기만 반환
     */
    public DigestResult sendDigest(boolean dryRun, int collectedCount) {
        List<HottracksPurchaseOrder> unnotified = orderRepository.findByNotifiedAtIsNullOrderByDetectedAtAsc();
        if (unnotified.isEmpty()) {
            log.info("교보 발주 다이제스트 - 신규 발주 없음 (발송 생략)");
            return new DigestResult(collectedCount, 0, 0, false, "신규 발주 없음");
        }

        String text = buildMessage(unnotified);
        if (dryRun) {
            return new DigestResult(collectedCount, unnotified.size(), 0, false, text);
        }

        boolean sent = slackNotifier.send(text);

        // 실제로 발송된 경우에만 알림 완료(notified_at)로 표시한다.
        // 웹훅 미설정·Slack 오류로 못 보냈는데 표시해 버리면 그 발주는 설정을 고친 뒤에도 영영 알림이 안 간다
        // (2026-09-01 창원점 사례). 미발송이면 다음 다이제스트에서 다시 시도한다.
        if (!sent) {
            log.warn("교보 발주 다이제스트 미발송 — {}건은 알림 완료로 표시하지 않음(다음 다이제스트에 재시도)", unnotified.size());
            return new DigestResult(collectedCount, unnotified.size(), 0, false, text);
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
        return new DigestResult(collectedCount, unnotified.size(), toMark.size(), sent, text);
    }

    private String buildMessage(List<HottracksPurchaseOrder> orders) {
        StringBuilder sb = new StringBuilder();
        sb.append("📦 교보 신규 발주 ").append(orders.size()).append("건\n");
        for (HottracksPurchaseOrder o : orders) {
            sb.append("• ").append(o.getPlorRdpName() == null ? o.getPlorRdpCode() : o.getPlorRdpName())
                    .append(" / 발주번호 ").append(o.getPlorNum())
                    .append(" / ").append(formatDate(o.getPlorDate()))
                    .append(" / ").append(o.getSumPlorQntt() == null ? "?" : o.getSumPlorQntt()).append("개")
                    .append(" / ").append(o.getPlorPrgsCdtnName() == null ? "" : o.getPlorPrgsCdtnName())
                    .append("\n");
        }
        sb.append("\n30-we.com에서 거래명세서를 확인·등록해 주세요.");
        return sb.toString();
    }

    private String formatDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) {
            return yyyymmdd == null ? "" : yyyymmdd;
        }
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }
}
