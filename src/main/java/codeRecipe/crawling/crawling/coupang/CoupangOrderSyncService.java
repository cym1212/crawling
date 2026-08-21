package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.domain.CoupangProduct;
import codeRecipe.crawling.crawling.domain.CoupangSales;
import codeRecipe.crawling.crawling.repository.CoupangProductRepository;
import codeRecipe.crawling.crawling.repository.CoupangSalesRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 로켓그로스 주문 API를 날짜 단위로 수집해 coupang_sales(일별 × 옵션 집계)에 upsert.
 * 하루씩 조회하므로 결제일 버킷팅이 명확하고, 같은 날짜 재수집은 덮어쓰기라 멱등이다.
 * 주문 응답의 상품명으로 coupang_product 미매핑 상품도 보강한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangOrderSyncService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long RG_PAGE_INTERVAL_MS = 1200; // 주문 API 분당 50회 제한
    private static final int MAX_PAGES = 200;
    private static final int MAX_RANGE_DAYS = 40; // 쿠팡이 과거 30일까지만 제공 — 여유 포함 상한

    private final CoupangApiClient coupangApiClient;
    private final CoupangSalesRepository coupangSalesRepository;
    private final CoupangProductRepository coupangProductRepository;

    /** 페이지 누적용 집계 단위 (vendorItemId 기준) */
    @Getter
    public static final class OrderAggregate {
        private int quantity;
        private long amount;
        private String productName;

        void add(int quantity, long amount, String productName) {
            this.quantity += quantity;
            this.amount += amount;
            if (this.productName == null) {
                this.productName = productName;
            }
        }
    }

    /**
     * 주문 API 응답의 data 배열을 vendorItemId 기준으로 누적 집계한다 (순수 함수 — 단위 테스트 대상).
     * 금액 = salesQuantity × unitSalesPrice 합.
     */
    public static Map<Long, OrderAggregate> aggregatePage(JsonNode dataArray, Map<Long, OrderAggregate> acc) {
        for (JsonNode orderNode : dataArray) {
            for (JsonNode itemNode : orderNode.path("orderItems")) {
                JsonNode vidNode = itemNode.path("vendorItemId");
                if (vidNode.isMissingNode() || vidNode.isNull()) {
                    continue;
                }
                long vendorItemId = vidNode.asLong();
                int quantity = itemNode.path("salesQuantity").asInt(0);
                long unitPrice = itemNode.path("unitSalesPrice").asLong(0);
                String productName = CoupangJsonUtils.textOrNull(itemNode, "productName");
                acc.computeIfAbsent(vendorItemId, k -> new OrderAggregate())
                        .add(quantity, (long) quantity * unitPrice, productName);
            }
        }
        return acc;
    }

    /**
     * [from, to] 구간을 하루 단위로 수집·집계·저장한다.
     * @return 저장(신규/갱신)된 일별 집계 행 전체
     */
    public List<CoupangSales> syncOrders(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("startDate가 endDate보다 늦습니다: " + from + " > " + to);
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("조회 범위는 최대 " + MAX_RANGE_DAYS + "일입니다 (쿠팡은 과거 30일까지만 제공)");
        }

        // 상품명 매핑은 구간 전체에서 한 번만 로드
        Map<Long, CoupangProduct> mappingByVendorItemId = new HashMap<>();
        for (CoupangProduct product : coupangProductRepository.findAll()) {
            mappingByVendorItemId.put(product.getVendorItemId(), product);
        }

        List<CoupangSales> allSaved = new ArrayList<>();
        Map<Long, CoupangProduct> nameBackfills = new LinkedHashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            allSaved.addAll(syncSingleDate(date, mappingByVendorItemId, nameBackfills));
        }

        // 주문에서 발견한 상품명으로 미매핑 coupang_product 보강
        if (!nameBackfills.isEmpty()) {
            coupangProductRepository.saveAll(new ArrayList<>(nameBackfills.values()));
            log.info("주문 데이터로 상품명 매핑 보강 {}건", nameBackfills.size());
        }
        log.info("쿠팡 판매 수집 완료 {} ~ {} savedRows={}", from, to, allSaved.size());
        return allSaved;
    }

    private List<CoupangSales> syncSingleDate(LocalDate date, Map<Long, CoupangProduct> mappingByVendorItemId,
                                              Map<Long, CoupangProduct> nameBackfills) {
        String dateParam = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        Map<Long, OrderAggregate> aggByVendorItemId = new LinkedHashMap<>();
        String nextToken = null;
        int pages = 0;
        Set<String> seenTokens = new HashSet<>();

        do {
            JsonNode response = coupangApiClient.getRocketGrowthOrders(dateParam, dateParam, nextToken);
            JsonNode data = response.path("data");
            if (!data.isArray()) {
                throw new CoupangApiException(
                        "쿠팡 주문 응답 형식 오류: " + response.path("message").asText(""), null, null);
            }
            aggregatePage(data, aggByVendorItemId);
            nextToken = CoupangJsonUtils.textOrNull(response, "nextToken");
            if (nextToken != null) {
                if (!seenTokens.add(nextToken)) {
                    log.warn("쿠팡 주문 nextToken 반복 감지 - 페이징 중단 date={} token={}", date, nextToken);
                    break;
                }
                sleep(RG_PAGE_INTERVAL_MS);
            }
            pages++;
        } while (nextToken != null && pages < MAX_PAGES);

        if (aggByVendorItemId.isEmpty()) {
            log.info("쿠팡 판매 수집 {} - 주문 없음", date);
            return List.of();
        }

        Map<Long, CoupangSales> existingByVendorItemId = new HashMap<>();
        for (CoupangSales sales : coupangSalesRepository.findBySalesDate(date)) {
            existingByVendorItemId.put(sales.getVendorItemId(), sales);
        }

        LocalDateTime collectedAt = LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS);
        List<CoupangSales> toSave = new ArrayList<>();
        for (Map.Entry<Long, OrderAggregate> entry : aggByVendorItemId.entrySet()) {
            Long vendorItemId = entry.getKey();
            OrderAggregate agg = entry.getValue();

            CoupangProduct mapping = mappingByVendorItemId.get(vendorItemId);
            String name = agg.getProductName() != null ? agg.getProductName()
                    : (mapping != null ? mapping.displayName() : null);

            // 매핑에 없는 상품이 주문에 등장하면 주문 상품명으로 매핑 보강
            if (mapping == null && agg.getProductName() != null && !nameBackfills.containsKey(vendorItemId)) {
                nameBackfills.put(vendorItemId, CoupangProduct.builder()
                        .vendorItemId(vendorItemId)
                        .productName(agg.getProductName())
                        .updatedAt(collectedAt)
                        .build());
            }

            CoupangSales existing = existingByVendorItemId.get(vendorItemId);
            toSave.add(existing == null
                    ? CoupangSales.builder()
                            .salesDate(date)
                            .vendorItemId(vendorItemId)
                            .productName(name)
                            .quantity(agg.getQuantity())
                            .salesAmount(agg.getAmount())
                            .collectedAt(collectedAt)
                            .build()
                    : existing.toBuilder()
                            .productName(name != null ? name : existing.getProductName())
                            .quantity(agg.getQuantity())
                            .salesAmount(agg.getAmount())
                            .collectedAt(collectedAt)
                            .build());
        }
        List<CoupangSales> saved = coupangSalesRepository.saveAll(toSave);
        log.info("쿠팡 판매 수집 {} - {}개 옵션 저장", date, saved.size());
        return saved;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoupangApiException("쿠팡 주문 페이징 대기 중 인터럽트", null, e);
        }
    }
}
