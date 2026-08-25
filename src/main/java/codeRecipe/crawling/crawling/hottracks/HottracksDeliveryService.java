package codeRecipe.crawling.crawling.hottracks;

import codeRecipe.crawling.crawling.domain.HottracksPurchaseOrder;
import codeRecipe.crawling.crawling.domain.HottracksPurchaseOrderItem;
import codeRecipe.crawling.crawling.repository.HottracksPurchaseOrderItemRepository;
import codeRecipe.crawling.crawling.repository.HottracksPurchaseOrderRepository;
import codeRecipe.crawling.crawling.util.BarcodeNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 교보 납품등록 (30-we.com [납품확인] 버튼이 내부 API로 호출).
 * 발주의 납품확인 페이지에 명세서 수량을 입력하고 임시저장/납품확인을 수행한다.
 * 쿠팡 CoupangFulfillmentService.ship() 의 멱등·상태기록 패턴을 따른다.
 *
 * <p>안전장치: {@code hottracks.delivery.allow-final-confirm}(기본 false)가 false면
 * 요청 mode가 CMPLT여도 TMPR(임시저장)로 강등한다. 운영 전환 시 true로.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HottracksDeliveryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final HottracksPurchaseOrderRepository orderRepository;
    private final HottracksPurchaseOrderItemRepository orderItemRepository;
    private final HottracksDeliverBotExecutor botExecutor;

    @Value("${hottracks.delivery.allow-final-confirm:false}")
    private boolean allowFinalConfirm;

    public record DeliverRequestItem(String barcode, int qty) {
    }

    public record DeliverResult(String plorNum, boolean confirmed, String mode,
                                int filledRows, List<String> unmatched, String message) {
    }

    /**
     * 납품등록 실행.
     * @param requestedMode "TMPR" 또는 "CMPLT". allow-final-confirm=false면 CMPLT는 TMPR로 강등.
     */
    public DeliverResult deliver(String plorRdpCode, String plorDate, String plorNum,
                                 List<DeliverRequestItem> items, String requestedMode) throws Exception {
        HottracksPurchaseOrder order = orderRepository
                .findByPlorRdpCodeAndPlorDateAndPlorNum(plorRdpCode, plorDate, plorNum)
                .orElseThrow(() -> new IllegalArgumentException(
                        "발주를 찾을 수 없습니다: " + plorRdpCode + "/" + plorDate + "/" + plorNum));

        if (order.getDeliveredAt() != null) {
            throw new IllegalStateException("이미 납품확인된 발주입니다: " + plorNum);
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("납품 항목이 비어 있습니다: " + plorNum);
        }

        // 안전장치: 최종확인 비활성 시 CMPLT → TMPR 강등
        String effectiveMode = "CMPLT".equals(requestedMode) && !allowFinalConfirm ? "TMPR" : requestedMode;
        if (!"TMPR".equals(effectiveMode) && !"CMPLT".equals(effectiveMode)) {
            effectiveMode = "TMPR";
        }
        if ("CMPLT".equals(requestedMode) && "TMPR".equals(effectiveMode)) {
            log.warn("allow-final-confirm=false → CMPLT 요청을 임시저장(TMPR)으로 강등. 발주={}", plorNum);
        }

        // 바코드 정규화 (crawling 저장분과 동일 규칙으로 매칭)
        List<HottracksDeliverBotExecutor.DeliverItem> botItems = new ArrayList<>();
        for (DeliverRequestItem it : items) {
            String bc = BarcodeNormalizer.normalize(it.barcode());
            if (bc != null && it.qty() > 0) {
                botItems.add(new HottracksDeliverBotExecutor.DeliverItem(bc, it.qty()));
            }
        }
        if (botItems.isEmpty()) {
            throw new IllegalArgumentException("유효한 납품 항목이 없습니다(바코드/수량 확인): " + plorNum);
        }

        LocalDateTime now = LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS);

        // 진행중 상태 기록
        orderRepository.save(order.toBuilder().status("DELIVERING").build());

        HottracksDeliverBotExecutor.BotResult result;
        try {
            result = botExecutor.runDeliver(plorRdpCode, plorDate, plorNum, botItems, effectiveMode);
        } catch (Exception e) {
            orderRepository.save(order.toBuilder().status("FAILED").lastError(e.getMessage()).build());
            throw e;
        }

        if (!result.ok()) {
            orderRepository.save(order.toBuilder().status("FAILED").lastError(result.message()).build());
            throw new IllegalStateException("납품등록 실패: " + result.message());
        }

        // 아이템별 납품량 반영(관측용)
        Map<String, Integer> qtyByBarcode = new HashMap<>();
        for (HottracksDeliverBotExecutor.DeliverItem it : botItems) {
            qtyByBarcode.put(it.barcode(), it.qty());
        }
        List<HottracksPurchaseOrderItem> savedItems = orderItemRepository
                .findByPurchaseOrderId(order.getPurchaseOrderId());
        List<HottracksPurchaseOrderItem> toUpdate = new ArrayList<>();
        for (HottracksPurchaseOrderItem item : savedItems) {
            Integer q = qtyByBarcode.get(item.getBarcode());
            if (q != null) {
                toUpdate.add(item.toBuilder().prosQntt(q).matchStatus("MATCHED").build());
            }
        }
        orderItemRepository.saveAll(toUpdate);

        boolean confirmed = "CMPLT".equals(effectiveMode);
        HottracksPurchaseOrder.HottracksPurchaseOrderBuilder b = order.toBuilder()
                .status(confirmed ? "DELIVERED" : "DELIVERED_TMP")
                .deliveryMode(effectiveMode)
                .lastError(null);
        if (confirmed) {
            b.deliveredAt(now);
        }
        orderRepository.save(b.build());

        log.info("교보 납품등록 완료 발주={} mode={} 입력행={}", plorNum, effectiveMode, result.filledRows());
        return new DeliverResult(plorNum, confirmed, effectiveMode,
                result.filledRows(), result.unmatched(), result.message());
    }
}
