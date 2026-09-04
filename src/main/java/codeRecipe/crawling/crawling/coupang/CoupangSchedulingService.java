package codeRecipe.crawling.crawling.coupang;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 쿠팡 관련 스케줄 잡. 기존 크롤링(01~05시)·슬랙 보고(10시)와 분리 운영.
 * 스케줄러 풀 사이즈가 1이라 모든 잡은 순차 실행된다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangSchedulingService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CoupangProductSyncService productSyncService;
    private final CoupangInventorySyncService inventorySyncService;
    private final CoupangRestockService restockService;
    private final CoupangOrderSyncService orderSyncService;
    private final CoupangMarketplaceOrderService marketplaceOrderService;
    private final CoupangInboundTrackingService inboundTrackingService;
    private final CoupangSlackNotifier coupangSlackNotifier;

    /** 판매자배송 주문 수집 + 다이제스트 발송 (기본: 매일 09:00, 12:00) */
    @Scheduled(cron = "${coupang.schedule.order-digest-cron:0 0 9,12 * * *}", zone = "Asia/Seoul")
    public void runOrderDigest() {
        runSafely("판매자배송 주문 다이제스트", marketplaceOrderService::runDigestJob);
    }

    /**
     * 판매자배송 주문 수집 + 상태 동기화만 — 슬랙 발송 없이 DB를 최신화한다 (기본: 매시 5분부터 10분 간격).
     * 신규 주문·Wing 직접 처리·취소가 최대 10분 내 30-we 화면에 반영되고, 알림은 다이제스트(08/12시)가 전담.
     * 실행 시각은 재고 동기화(:10)·다이제스트(정각)와 겹치지 않게 5분 오프셋.
     */
    @Scheduled(cron = "${coupang.schedule.order-collect-cron:0 5/10 * * * *}", zone = "Asia/Seoul")
    public void runOrderCollect() {
        runSafely("판매자배송 주문 수집", () -> {
            marketplaceOrderService.syncPendingOrderStatuses();
            marketplaceOrderService.collectNewOrders();
        });
    }

    /** 판매(주문) 수집 (기본: 매일 05:30). 지연 반영 보정을 위해 최근 3일 창을 다시 수집 (멱등) */
    @Scheduled(cron = "${coupang.schedule.order-cron:0 30 5 * * *}", zone = "Asia/Seoul")
    public void runOrderSync() {
        runSafely("쿠팡 판매(주문) 수집", () -> {
            LocalDate yesterday = LocalDate.now(SEOUL).minusDays(1);
            orderSyncService.syncOrders(yesterday.minusDays(2), yesterday);
        });
    }

    /** 상품명 매핑 동기화 (기본: 매일 06:00) */
    @Scheduled(cron = "${coupang.schedule.product-cron:0 0 6 * * *}", zone = "Asia/Seoul")
    public void runProductSync() {
        runSafely("쿠팡 상품 매핑 동기화", () -> productSyncService.syncProducts(false));
    }

    /** 재고 동기화 (기본: 매시 10분) */
    @Scheduled(cron = "${coupang.schedule.inventory-cron:0 10 * * * *}", zone = "Asia/Seoul")
    public void runInventorySync() {
        runSafely("쿠팡 재고 동기화", inventorySyncService::syncInventory);
    }

    /** 부족 재고 계산 + 슬랙 알림 (기본: 매일 07:00) */
    @Scheduled(cron = "${coupang.schedule.restock-cron:0 0 7 * * *}", zone = "Asia/Seoul")
    public void runRestock() {
        runSafely("쿠팡 재입고 제안", restockService::generateAndNotify);
    }

    /** 로켓그로스 입고 반영 추적 (기본: 매일 07:40 — 판매 수집 05:30·재고 매시 동기화 이후) */
    @Scheduled(cron = "${coupang.inbound.tracking-cron:0 40 7 * * *}", zone = "Asia/Seoul")
    public void runInboundTracking() {
        runSafely("로켓그로스 입고 반영 추적", inboundTrackingService::trackReceipts);
    }

    private void runSafely(String jobName, Runnable job) {
        try {
            job.run();
            log.info("{} 완료", jobName);
        } catch (Exception e) {
            log.error("{} 실패: {}", jobName, e.getMessage(), e);
            try {
                coupangSlackNotifier.send("⚠️ [쿠팡] " + jobName + " 실패: " + e.getMessage());
            } catch (Exception slackError) {
                log.error("슬랙 오류 알림 전송 실패", slackError);
            }
        }
    }
}
