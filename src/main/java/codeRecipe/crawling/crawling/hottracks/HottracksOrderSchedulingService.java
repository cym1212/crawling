package codeRecipe.crawling.crawling.hottracks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 교보 발주 스케줄 잡.
 * 스케줄러 풀 사이즈가 1이라 모든 잡이 순차 실행되므로, 기존 서점 크롤링(01~05시)·
 * 쿠팡 잡과 시간대가 겹치지 않게 크론을 배치한다.
 *
 * <p>- 매시 수집(알림 없음): 신규 발주를 DB에 계속 쌓아둔다.
 * <p>- 정시 다이제스트(알림): 미알림 발주를 모아 Slack 발송 (기본 08:15, 12:15).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HottracksOrderSchedulingService {

    private final HottracksOrderService orderService;
    private final HottracksSlackNotifier slackNotifier;

    /** 발주 수집만 (매시 40분 — 서점 크롤링 정시대와 분리). 알림 없음. */
    @Scheduled(cron = "${hottracks.schedule.order-collect-cron:0 40 * * * *}", zone = "Asia/Seoul")
    public void runOrderCollect() {
        runSafely("교보 발주 수집", orderService::collectOnly);
    }

    /** 발주 수집 + 다이제스트 발송 (기본: 08:15, 12:15). 미알림 발주를 Slack으로. */
    @Scheduled(cron = "${hottracks.schedule.order-digest-cron:0 15 8,12 * * *}", zone = "Asia/Seoul")
    public void runOrderDigest() {
        runSafely("교보 발주 다이제스트", orderService::runDigestJob);
    }

    @FunctionalInterface
    private interface Job {
        void run() throws Exception;
    }

    private void runSafely(String jobName, Job job) {
        try {
            job.run();
            log.info("{} 완료", jobName);
        } catch (Exception e) {
            log.error("{} 실패: {}", jobName, e.getMessage(), e);
            try {
                slackNotifier.send("⚠️ [교보] " + jobName + " 실패: " + e.getMessage());
            } catch (Exception slackError) {
                log.error("슬랙 오류 알림 전송 실패", slackError);
            }
        }
    }
}
