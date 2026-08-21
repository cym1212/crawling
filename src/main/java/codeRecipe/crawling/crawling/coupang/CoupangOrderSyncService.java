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

import java.time.Instant;
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
import java.util.TreeMap;

/**
 * 로켓그로스 주문 API를 기간 조회해 coupang_sales(일별 × 옵션 집계)에 upsert.
 *
 * 주의(실측으로 확인된 쿠팡 API 실제 동작):
 * - paidDateFrom=paidDateTo 단일 날짜 조회는 항상 빈 결과를 반환한다 (paidDateTo 사실상 미포함).
 *   → 기간을 통째로 조회하고, 각 주문의 paidAt(epoch millis)을 한국시간 날짜로 변환해 버킷팅한다.
 * - unitSalesPrice는 "4900.0" 같은 소수점 문자열로 온다 → asDouble로 파싱.
 * 같은 날짜 재수집은 덮어쓰기라 멱등이며, 주문 응답의 상품명으로 coupang_product 미매핑 상품도 보강한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoupangOrderSyncService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long RG_PAGE_INTERVAL_MS = 1200; // 주문 API 분당 50회 제한
    private static final int MAX_PAGES = 200;
    private static final int MAX_RANGE_DAYS = 40;   // 쿠팡이 과거 30일까지만 제공 — 여유 포함 상한
    private static final int CHUNK_DAYS = 28;       // API 호출당 최대 조회 폭 (30일 제한 준수, +1일 확장 감안)

    private final CoupangApiClient coupangApiClient;
    private final CoupangSalesRepository coupangSalesRepository;
    private final CoupangProductRepository coupangProductRepository;

    /** 옵션(vendorItemId) 단위 집계 */
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
     * 주문 API 응답의 data 배열을 "결제일(한국시간) × vendorItemId" 기준으로 누적 집계한다
     * (순수 함수 — 단위 테스트 대상). paidAt은 epoch millis(숫자 또는 숫자 문자열).
     */
    public static Map<LocalDate, Map<Long, OrderAggregate>> aggregateOrders(
            JsonNode dataArray, Map<LocalDate, Map<Long, OrderAggregate>> acc) {
        for (JsonNode orderNode : dataArray) {
            long paidAtMillis = orderNode.path("paidAt").asLong(0);
            if (paidAtMillis <= 0) {
                continue;
            }
            LocalDate paidDate = Instant.ofEpochMilli(paidAtMillis).atZone(SEOUL).toLocalDate();
            for (JsonNode itemNode : orderNode.path("orderItems")) {
                JsonNode vidNode = itemNode.path("vendorItemId");
                if (vidNode.isMissingNode() || vidNode.isNull()) {
                    continue;
                }
                long vendorItemId = vidNode.asLong();
                int quantity = itemNode.path("salesQuantity").asInt(0);
                long unitPrice = Math.round(itemNode.path("unitSalesPrice").asDouble(0)); // "4900.0" 문자열 대응
                String productName = CoupangJsonUtils.textOrNull(itemNode, "productName");
                acc.computeIfAbsent(paidDate, k -> new LinkedHashMap<>())
                        .computeIfAbsent(vendorItemId, k -> new OrderAggregate())
                        .add(quantity, quantity * unitPrice, productName);
            }
        }
        return acc;
    }

    /**
     * 결제일이 [from, to]인 주문을 수집·집계·저장한다.
     * @return 저장(신규/갱신)된 일별 집계 행 전체
     */
    public List<CoupangSales> syncOrders(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("startDate가 endDate보다 늦습니다: " + from + " > " + to);
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("조회 범위는 최대 " + MAX_RANGE_DAYS + "일입니다 (쿠팡은 과거 30일까지만 제공)");
        }

        // 기간을 청크로 나눠 조회 (경계 미포함 대응으로 각 청크 종료일 +1일 확장)
        Map<LocalDate, Map<Long, OrderAggregate>> aggByDate = new TreeMap<>();
        LocalDate chunkFrom = from;
        while (!chunkFrom.isAfter(to)) {
            LocalDate chunkTo = chunkFrom.plusDays(CHUNK_DAYS - 1).isAfter(to) ? to : chunkFrom.plusDays(CHUNK_DAYS - 1);
            fetchRange(chunkFrom, chunkTo.plusDays(1), aggByDate);
            chunkFrom = chunkTo.plusDays(1);
        }
        // +1일 확장으로 섞여 들어온 범위 밖 날짜 제거
        aggByDate.keySet().removeIf(d -> d.isBefore(from) || d.isAfter(to));

        // 상품명 매핑 로드
        Map<Long, CoupangProduct> mappingByVendorItemId = new HashMap<>();
        for (CoupangProduct product : coupangProductRepository.findAll()) {
            mappingByVendorItemId.put(product.getVendorItemId(), product);
        }

        LocalDateTime collectedAt = LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS);
        List<CoupangSales> allSaved = new ArrayList<>();
        Map<Long, CoupangProduct> nameBackfills = new LinkedHashMap<>();

        for (Map.Entry<LocalDate, Map<Long, OrderAggregate>> dateEntry : aggByDate.entrySet()) {
            LocalDate date = dateEntry.getKey();
            Map<Long, CoupangSales> existingByVendorItemId = new HashMap<>();
            for (CoupangSales sales : coupangSalesRepository.findBySalesDate(date)) {
                existingByVendorItemId.put(sales.getVendorItemId(), sales);
            }

            List<CoupangSales> toSave = new ArrayList<>();
            for (Map.Entry<Long, OrderAggregate> entry : dateEntry.getValue().entrySet()) {
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
            allSaved.addAll(coupangSalesRepository.saveAll(toSave));
        }

        if (!nameBackfills.isEmpty()) {
            coupangProductRepository.saveAll(new ArrayList<>(nameBackfills.values()));
            log.info("주문 데이터로 상품명 매핑 보강 {}건", nameBackfills.size());
        }
        log.info("쿠팡 판매 수집 완료 {} ~ {} 판매일 {}일 / 집계 {}행", from, to, aggByDate.size(), allSaved.size());
        return allSaved;
    }

    /** [from, toExclusive) 구간을 페이징 조회하며 누적 집계 */
    private void fetchRange(LocalDate from, LocalDate toExclusive,
                            Map<LocalDate, Map<Long, OrderAggregate>> acc) {
        String fromParam = from.format(DateTimeFormatter.BASIC_ISO_DATE);
        String toParam = toExclusive.format(DateTimeFormatter.BASIC_ISO_DATE);
        String nextToken = null;
        int pages = 0;
        Set<String> seenTokens = new HashSet<>();

        do {
            JsonNode response = coupangApiClient.getRocketGrowthOrders(fromParam, toParam, nextToken);
            JsonNode data = response.path("data");
            if (!data.isArray()) {
                throw new CoupangApiException(
                        "쿠팡 주문 응답 형식 오류: " + response.path("message").asText(""), null, null);
            }
            aggregateOrders(data, acc);
            nextToken = CoupangJsonUtils.textOrNull(response, "nextToken");
            if (nextToken != null) {
                if (!seenTokens.add(nextToken)) {
                    log.warn("쿠팡 주문 nextToken 반복 감지 - 페이징 중단 token={}", nextToken);
                    break;
                }
                sleep(RG_PAGE_INTERVAL_MS);
            }
            pages++;
        } while (nextToken != null && pages < MAX_PAGES);
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
