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

    // 발주 원본(PDF)을 올릴 사내 CDN(refrigerator) 설정. endpoint 빈 값이면 미전달 = 원본 저장 스킵.
    @Value("${hottracks.excel.refrigerator-endpoint:https://refrigerator.logipasta.com/v1/file}")
    private String fridgeEndpoint;
    @Value("${hottracks.excel.refrigerator-bucket:withcookie-bucket}")
    private String fridgeBucket;
    @Value("${hottracks.excel.refrigerator-path:hottracks-order}")
    private String fridgePath;
    // 원본 저장 기능 on/off (기본 on). off면 파이썬에 endpoint 미전달.
    @Value("${hottracks.excel.enabled:true}")
    private boolean excelEnabled;

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

        List<String> cmd = new ArrayList<>(List.of(
                pythonPath, tempScript.getAbsolutePath(),
                loginUrl, homeUrl, username, password));
        // 원본 저장 활성 시에만 refrigerator 인자 3개(endpoint/bucket/path) 전달.
        // 미전달이면 파이썬이 원본 다운로드를 스킵한다.
        if (excelEnabled && fridgeEndpoint != null && !fridgeEndpoint.isBlank()) {
            cmd.add(fridgeEndpoint);
            cmd.add(fridgeBucket != null ? fridgeBucket : "withcookie-bucket");
            cmd.add(fridgePath != null ? fridgePath : "hottracks-order");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
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
        // 응답 형식: {"orders":[...], "activeOrderKeys":[...]} (사라짐 감지).
        // 하위호환: 과거 배열 형식이면 그대로 orders로 취급.
        JsonNode ordersNode = root.isArray() ? root : root.get("orders");
        if (ordersNode == null || !ordersNode.isArray()) {
            throw new RuntimeException("교보 발주 응답 형식 오류(orders 배열 없음)");
        }

        LocalDateTime now = LocalDateTime.now(SEOUL);
        int saved = 0;

        for (JsonNode od : ordersNode) {
            String plorRdpCode = text(od, "plorRdpCode");
            String plorDate = text(od, "plorDate");
            String plorNum = text(od, "plorNum");
            if (plorRdpCode.isEmpty() || plorDate.isEmpty() || plorNum.isEmpty()) {
                log.warn("발주키 누락 행 건너뜀: {}", od);
                continue;
            }

            String excelUrl = text(od, "excelUrl");   // null/빈 문자열이면 미저장(refrigerator CDN URL)

            // 기수집 발주: 통째로 건너뛰되, 원본이 이번에 새로 저장됐고 아직 URL이 없으면 소급 기록
            var existing = orderRepository.findByPlorRdpCodeAndPlorDateAndPlorNum(plorRdpCode, plorDate, plorNum);
            if (existing.isPresent()) {
                HottracksPurchaseOrder ex = existing.get();
                if ((ex.getExcelCdnUrl() == null || ex.getExcelCdnUrl().isBlank()) && !excelUrl.isEmpty()) {
                    ex.setExcelCdnUrl(excelUrl);
                    ex.setExcelSavedAt(now);
                    orderRepository.save(ex);
                    log.info("교보 발주 원본 소급 저장: {}/{}/{} → {}", plorRdpCode, plorDate, plorNum, excelUrl);
                } else {
                    log.info("이미 수집된 발주 건너뜀: {}/{}/{}", plorRdpCode, plorDate, plorNum);
                }
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
                    .excelCdnUrl(excelUrl.isEmpty() ? null : excelUrl)
                    .excelSavedAt(excelUrl.isEmpty() ? null : now)
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

        // ── "발주확정에서 사라진 발주 = 처리됨(발주확인 눌림)" 감지 (사라짐 감지) ──
        // 홈 gridPlor(최근 약 7일)에 발주확정만 노출된다. 발주확인을 누르면 목록에서 빠진다.
        // bsight 미처리(delivered_at null) 발주 중 발주일이 조회범위 안인데 이번 홈 목록(activeOrderKeys)에
        // 없으면 = 처리된 것 → delivered 기록. 사람이 직접 처리하든 시스템이 처리하든 "사라짐"으로 판정.
        java.util.Set<String> activeKeys = new java.util.HashSet<>();
        JsonNode activeNode = root.get("activeOrderKeys");
        if (activeNode != null && activeNode.isArray()) {
            for (JsonNode ak : activeNode) {
                activeKeys.add(text(ak, "plorRdpCode") + "|" + text(ak, "plorDate") + "|" + text(ak, "plorNum"));
            }
        }
        // 조회범위 하한: 오늘 기준 7일 전(홈 기본범위 약 7일보다 좁게 잡아 "범위 밖이라 안 보임"을 사라짐으로 오판하지 않게).
        // plorDate는 "YYYYMMDD" 문자열이라 같은 형식으로 하한을 만들어 문자열 비교.
        String plorDateFrom = now.toLocalDate().minusDays(7).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

        int syncedGone = 0;
        // activeOrderKeys가 비정상(파싱 실패)일 땐 전량 delivered 오처리 위험 → activeNode 있을 때만 감지 수행.
        if (activeNode != null && activeNode.isArray()) {
            List<HottracksPurchaseOrder> pending =
                    orderRepository.findByDeliveredAtIsNullAndPlorDateGreaterThanEqual(plorDateFrom);
            for (HottracksPurchaseOrder ex : pending) {
                String key = ex.getPlorRdpCode() + "|" + ex.getPlorDate() + "|" + ex.getPlorNum();
                if (activeKeys.contains(key)) {
                    continue;  // 아직 발주확정에 남아있음 = 미처리
                }
                // 우리 봇이 처리 중/완료/실패 상태면 봇 흐름이 관리하므로 건드리지 않는다.
                String st = ex.getStatus();
                if ("DELIVERING".equals(st) || "DELIVERED".equals(st) || "DELIVERED_TMP".equals(st) || "FAILED".equals(st)) {
                    continue;
                }
                orderRepository.save(ex.toBuilder()
                        .status("DELIVERED")
                        .deliveryMode("MANUAL")   // 발주확정에서 사라짐 = 사람/시스템이 발주확인 처리
                        .deliveredAt(now)
                        .build());
                syncedGone++;
                log.info("교보 발주 발주확정에서 사라짐 → delivered 기록: {}/{}/{}",
                        ex.getPlorRdpCode(), ex.getPlorDate(), ex.getPlorNum());
            }
        }
        if (syncedGone > 0) {
            log.info("교보 사라짐 감지 발주 {}건 delivered 반영", syncedGone);
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
