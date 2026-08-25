package codeRecipe.crawling.crawling.hottracks;

import codeRecipe.crawling.crawling.domain.HottracksPurchaseOrder;
import codeRecipe.crawling.crawling.domain.HottracksPurchaseOrderItem;
import codeRecipe.crawling.crawling.repository.HottracksPurchaseOrderItemRepository;
import codeRecipe.crawling.crawling.repository.HottracksPurchaseOrderRepository;
import codeRecipe.crawling.crawling.util.BarcodeNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 교보 발주 수집 실행기. HottracksOrderCrawler.py 를 실행해 발주 목록+상세를 받아
 * HottracksPurchaseOrder(+Item)에 저장한다. 발주키 중복은 건너뛴다.
 * 기존 HottracksPythonScriptExecutor 패턴을 따른다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HottracksOrderScriptExecutor {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String VNDR_CODE = "0817671"; // 비사이트

    @Value("${app.login.hottracks-url}")
    private String loginUrl;

    @Value("${app.login.hottracks-username}")
    private String username;

    @Value("${app.login.hottracks-password}")
    private String password;

    // 발주 목록이 있는 홈 URL (application-hottracks.yml 에 추가)
    @Value("${app.hottracks-order-home-url:https://partner.hottracks.co.kr/mainFinder.do?method=listView}")
    private String homeUrl;

    private final HottracksPurchaseOrderRepository orderRepository;
    private final HottracksPurchaseOrderItemRepository orderItemRepository;

    /** 발주 수집 실행. 반환: 새로 저장된 발주 수 */
    public int collectOrders() throws Exception {
        String pythonPath = new File("venv/bin/python3").getAbsolutePath();

        ClassPathResource resource = new ClassPathResource("scripts/HottracksOrderCrawler.py");
        File tempScript = File.createTempFile("hottracks_order", ".py");
        try (InputStream in = resource.getInputStream();
             FileOutputStream out = new FileOutputStream(tempScript)) {
            byte[] buf = new byte[1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(
                pythonPath, tempScript.getAbsolutePath(),
                loginUrl, homeUrl, username, password);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            while ((line = errorReader.readLine()) != null) {
                log.info("[Hottracks-Order-ERR] {}", line);
            }
        }
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return parseAndSave(output.toString());
    }

    private int parseAndSave(String jsonData) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonData);

        if (root.has("error")) {
            String msg = root.get("error").asText();
            log.error("교보 발주 크롤링 실패: {}", msg);
            throw new RuntimeException("교보 발주 크롤링 실패: " + msg);
        }
        if (!root.isArray()) {
            throw new RuntimeException("교보 발주 응답 형식 오류(배열 아님)");
        }

        LocalDateTime now = LocalDateTime.now(SEOUL);
        int saved = 0;

        for (JsonNode od : root) {
            String plorRdpCode = text(od, "plorRdpCode");
            String plorDate = text(od, "plorDate");
            String plorNum = text(od, "plorNum");
            if (plorRdpCode.isEmpty() || plorDate.isEmpty() || plorNum.isEmpty()) {
                log.warn("발주키 누락 행 건너뜀: {}", od);
                continue;
            }

            if (orderRepository.existsByPlorRdpCodeAndPlorDateAndPlorNum(plorRdpCode, plorDate, plorNum)) {
                log.info("이미 수집된 발주 건너뜀: {}/{}/{}", plorRdpCode, plorDate, plorNum);
                continue;
            }

            HottracksPurchaseOrder order = HottracksPurchaseOrder.builder()
                    .plorRdpCode(plorRdpCode)
                    .plorDate(plorDate)
                    .plorNum(plorNum)
                    .plorRdpName(text(od, "plorRdpName"))
                    .vndrCode(od.hasNonNull("vndrCode") && !text(od, "vndrCode").isEmpty()
                            ? text(od, "vndrCode") : VNDR_CODE)
                    .plorPrgsCdtnCode(text(od, "plorPrgsCdtnCode"))
                    .plorPrgsCdtnName(text(od, "plorPrgsCdtnName"))
                    .sumPlorQntt(parseIntSafe(text(od, "sumPlorQntt")))
                    .plorDecrId(text(od, "plorDecrId"))
                    .status("NEW")
                    .detectedAt(now)
                    .collectedAt(now)
                    .build();
            order = orderRepository.save(order);

            List<HottracksPurchaseOrderItem> items = new ArrayList<>();
            JsonNode itemsNode = od.get("items");
            if (itemsNode != null && itemsNode.isArray()) {
                for (JsonNode it : itemsNode) {
                    String barcode = BarcodeNormalizer.normalize(text(it, "barcode"));
                    if (barcode == null) {
                        log.warn("교보 발주 상품 바코드 없음 건너뜀: 발주={} 상품={}", plorNum, text(it, "productName"));
                        continue;
                    }
                    items.add(HottracksPurchaseOrderItem.builder()
                            .purchaseOrderId(order.getPurchaseOrderId())
                            .barcode(barcode)
                            .cmdtId(text(it, "cmdtId"))
                            .productName(text(it, "productName"))
                            .plorQntt(parseIntSafe(text(it, "plorQntt")))
                            .saleUnpr(parseLongSafe(text(it, "saleUnpr")))
                            .build());
                }
            }
            orderItemRepository.saveAll(items);
            saved++;
            log.info("교보 발주 저장: {}/{}/{} 상품 {}개", plorRdpCode, plorDate, plorNum, items.size());
        }
        return saved;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? "" : v.asText().trim();
    }

    private Integer parseIntSafe(String v) {
        try {
            return Integer.valueOf(v.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLongSafe(String v) {
        try {
            return Long.valueOf(v.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
