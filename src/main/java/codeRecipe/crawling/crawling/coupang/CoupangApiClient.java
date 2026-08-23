package codeRecipe.crawling.crawling.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@Slf4j
public class CoupangApiClient {

    // 쿠팡 HMAC 서명의 signed-date 포맷 (GMT+0 기준)
    private static final DateTimeFormatter SIGNED_DATE_FORMAT = DateTimeFormatter.ofPattern("yyMMdd'T'HHmmss'Z'");

    // 쿠팡 전역 호출 제한(판매자당 초당 5회) 보호용 최소 호출 간격
    private static final long MIN_INTERVAL_MS = 250;

    private final CoupangApiProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // throttle()에서만 접근 (synchronized)
    private long lastRequestAt = 0L;

    /**
     * 로켓그로스(로켓창고) 재고 요약 조회 (1페이지)
     * 호출 제한: 분당 50회 — 전체 페이징 순회 시 호출측에서 페이지 간 대기 필요
     */
    public JsonNode getRocketGrowthInventorySummaries(String nextToken) {
        String path = "/v2/providers/rg_open_api/apis/api/v1/vendors/" + properties.getVendorId()
                + "/rg/inventory/summaries";
        String query = (nextToken == null || nextToken.isBlank()) ? "" : "nextToken=" + nextToken;
        return getWithRetry(path, query);
    }

    /**
     * 로켓그로스 주문 목록 조회 (결제일 기준, 1페이지)
     * 호출 제한: 분당 50회, 조회 범위 최대 30일
     * @param paidDateFrom yyyyMMdd
     * @param paidDateTo   yyyyMMdd
     */
    public JsonNode getRocketGrowthOrders(String paidDateFrom, String paidDateTo, String nextToken) {
        String path = "/v2/providers/rg_open_api/apis/api/v1/vendors/" + properties.getVendorId() + "/rg/orders";
        String query = "paidDateFrom=" + paidDateFrom + "&paidDateTo=" + paidDateTo
                + ((nextToken == null || nextToken.isBlank()) ? "" : "&nextToken=" + nextToken);
        return getWithRetry(path, query);
    }

    /** 상품 목록 페이징 조회 (sellerProductId/sellerProductName, vendorItemId 없음) */
    public JsonNode getSellerProducts(String nextToken) {
        String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
        String query = "vendorId=" + properties.getVendorId() + "&maxPerPage=100"
                + ((nextToken == null || nextToken.isBlank()) ? "" : "&nextToken=" + nextToken);
        return getWithRetry(path, query);
    }

    /** 상품 상세 조회 (items[].vendorItemId, itemName — 승인 전 상품은 vendorItemId null) */
    public JsonNode getSellerProduct(long sellerProductId) {
        String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + sellerProductId;
        return getWithRetry(path, "");
    }

    /** 스로틀 적용 + 429 시 60초 대기 후 1회 재시도 */
    public JsonNode getWithRetry(String path, String query) {
        throttle();
        try {
            return get(path, query);
        } catch (CoupangApiException e) {
            if (!e.isTooManyRequests()) {
                throw e;
            }
            log.warn("쿠팡 API 429 발생 - 60초 대기 후 1회 재시도 path={}", path);
            sleep(60_000);
            throttle();
            return get(path, query);
        }
    }

    /**
     * @param query URL 인코딩된 쿼리스트링 ('?' 제외). 서명 대상 문자열에 그대로 포함되므로
     *              실제 요청 쿼리와 정확히 일치해야 한다.
     */
    public JsonNode get(String path, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, generateAuthorization("GET", path, query));
        headers.setContentType(MediaType.APPLICATION_JSON);

        String url = properties.getBaseUrl() + path + (query.isEmpty() ? "" : "?" + query);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return objectMapper.readTree(response.getBody());
        } catch (HttpStatusCodeException e) {
            log.error("쿠팡 API 호출 실패 status={} path={} body={}", e.getStatusCode(), path,
                    e.getResponseBodyAsString());
            throw new CoupangApiException("쿠팡 API 호출 실패: " + e.getStatusCode(), e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("쿠팡 API 응답 처리 실패 path={}", path, e);
            throw new CoupangApiException("쿠팡 API 응답 처리 실패", null, e);
        }
    }

    private synchronized void throttle() {
        long wait = lastRequestAt + MIN_INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoupangApiException("쿠팡 API 호출 대기 중 인터럽트", null, e);
        }
    }

    private String generateAuthorization(String method, String path, String query) {
        String signedDate = ZonedDateTime.now(ZoneOffset.UTC).format(SIGNED_DATE_FORMAT);
        String message = signedDate + method + path + query;
        String signature = hmacSha256Hex(properties.getSecretKey(), message);
        return String.format("CEA algorithm=HmacSHA256, access-key=%s, signed-date=%s, signature=%s",
                properties.getAccessKey(), signedDate, signature);
    }

    private String hmacSha256Hex(String secretKey, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] rawHmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(rawHmac.length * 2);
            for (byte b : rawHmac) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 서명 생성 실패", e);
        }
    }
}
