package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.SlackWebhookService;
import codeRecipe.crawling.crawling.domain.CoupangInventory;
import codeRecipe.crawling.crawling.domain.CoupangRestockSuggestion;
import codeRecipe.crawling.crawling.domain.RestockStatus;
import codeRecipe.crawling.crawling.repository.CoupangInventoryRepository;
import codeRecipe.crawling.crawling.repository.CoupangRestockSuggestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 최신 재고 스냅샷 기준 부족 재고 판단 → coupang_restock_suggestion 생성 → 슬랙 알림.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangRestockService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CoupangInventoryRepository coupangInventoryRepository;
    private final CoupangRestockSuggestionRepository suggestionRepository;
    private final CoupangRestockProperties restockProperties;
    private final SlackWebhookService slackWebhookService;

    public List<CoupangRestockSuggestion> generateSuggestions() {
        LocalDateTime maxCollectedAt = coupangInventoryRepository.findMaxCollectedAt();
        if (maxCollectedAt == null) {
            log.warn("쿠팡 재고 데이터가 없어 재입고 제안을 건너뜁니다.");
            return List.of();
        }
        // 마지막 동기화 시점 스냅샷만 사용 — 판매 종료 등으로 갱신이 끊긴 오래된 행 제외
        List<CoupangInventory> snapshot = coupangInventoryRepository.findByCollectedAt(maxCollectedAt);
        LocalDate today = LocalDate.now(SEOUL);
        List<CoupangRestockSuggestion> saved = new ArrayList<>();

        for (CoupangInventory inventory : snapshot) {
            Optional<RestockCalculator.Result> result = RestockCalculator.calculate(
                    inventory.getOrderableQuantity(),
                    inventory.getSalesCountLast30Days(),
                    restockProperties.getThresholdDays(),
                    restockProperties.getTargetDays(),
                    today);
            if (result.isEmpty()) {
                continue;
            }
            if (suggestionRepository.existsByVendorItemIdAndSuggestionDate(inventory.getVendorItemId(), today)) {
                continue; // 오늘자 제안이 이미 있음
            }
            RestockCalculator.Result r = result.get();
            try {
                saved.add(suggestionRepository.save(CoupangRestockSuggestion.builder()
                        .vendorItemId(inventory.getVendorItemId())
                        .productName(inventory.getProductName())
                        .currentQuantity(inventory.getOrderableQuantity())
                        .dailyAvgSales(r.dailyAvgSales())
                        .daysUntilStockout(r.daysUntilStockout())
                        .expectedStockoutDate(r.expectedStockoutDate())
                        .suggestedQuantity(r.suggestedQuantity())
                        .status(RestockStatus.SUGGESTED)
                        .suggestionDate(today)
                        .createdAt(LocalDateTime.now(SEOUL))
                        .build()));
            } catch (DataIntegrityViolationException e) {
                log.warn("재입고 제안 중복 저장 스킵 vendorItemId={} date={}", inventory.getVendorItemId(), today);
            }
        }
        log.info("쿠팡 재입고 제안 생성 완료 {}건", saved.size());
        return saved;
    }

    /** 제안 생성 후 슬랙 알림. 수동 트리거에서 결과 확인용으로 메시지를 반환한다. */
    public String generateAndNotify() {
        List<CoupangRestockSuggestion> suggestions = generateSuggestions();
        if (suggestions.isEmpty()) {
            log.info("쿠팡 재입고 제안 없음");
            return "재입고 제안 없음";
        }
        String message = buildSlackMessage(suggestions);
        slackWebhookService.sendMessage(message);
        return message;
    }

    private String buildSlackMessage(List<CoupangRestockSuggestion> suggestions) {
        StringBuilder message = new StringBuilder();
        message.append("📦 *쿠팡 로켓그로스 재입고 제안* (")
                .append(suggestions.get(0).getSuggestionDate())
                .append(")\n\n");
        int index = 1;
        for (CoupangRestockSuggestion s : suggestions) {
            String name = s.getProductName() != null ? s.getProductName() : "(상품명 미매핑)";
            message.append(String.format("%d. %s (옵션ID: %d)\n", index++, name, s.getVendorItemId()));
            message.append(String.format("   현재 재고 %,d개 / 일평균 판매 %s개 / 소진까지 약 %s일 (%s)\n",
                    s.getCurrentQuantity(), s.getDailyAvgSales(), s.getDaysUntilStockout(),
                    s.getExpectedStockoutDate()));
            message.append(String.format("   → 제안 입고 수량 %,d개\n\n", s.getSuggestedQuantity()));
        }
        message.append(String.format("총 %d건 / 기준: 소진 %d일 이내 제안, %d일치 보충",
                suggestions.size(), restockProperties.getThresholdDays(), restockProperties.getTargetDays()));
        return message.toString();
    }
}
