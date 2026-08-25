package codeRecipe.crawling.crawling.hottracks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * 교보 납품등록 봇(HottracksDeliverBot.py) 실행기.
 * 납품확인 페이지에 상품별 납품량을 입력하고 mode에 따라 임시저장/납품확인을 수행한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HottracksDeliverBotExecutor {

    @Value("${app.login.hottracks-url}")
    private String loginUrl;

    @Value("${app.login.hottracks-username}")
    private String username;

    @Value("${app.login.hottracks-password}")
    private String password;

    @Value("${app.hottracks-order-home-url:https://partner.hottracks.co.kr/mainFinder.do?method=listView}")
    private String homeUrl;

    /** 납품 항목: 바코드 + 납품수량 */
    public record DeliverItem(String barcode, int qty) {
    }

    /** 봇 실행 결과 */
    public record BotResult(boolean ok, int filledRows, List<String> unmatched, String mode, String message) {
    }

    /**
     * 납품등록 봇 실행.
     * @param mode "TMPR"(임시저장) 또는 "CMPLT"(납품확인)
     */
    public BotResult runDeliver(String plorRdpCode, String plorDate, String plorNum,
                                List<DeliverItem> items, String mode) throws Exception {
        String pythonPath = new File("venv/bin/python3").getAbsolutePath();

        ClassPathResource resource = new ClassPathResource("scripts/HottracksDeliverBot.py");
        File tempScript = File.createTempFile("hottracks_deliver", ".py");
        try (InputStream in = resource.getInputStream();
             FileOutputStream out = new FileOutputStream(tempScript)) {
            byte[] buf = new byte[1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }

        JSONArray itemsJson = new JSONArray();
        for (DeliverItem it : items) {
            itemsJson.put(new JSONObject().put("barcode", it.barcode()).put("qty", it.qty()));
        }

        ProcessBuilder pb = new ProcessBuilder(
                pythonPath, tempScript.getAbsolutePath(),
                loginUrl, homeUrl, username, password,
                plorRdpCode, plorDate, plorNum, itemsJson.toString(), mode);
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
                log.info("[Hottracks-Deliver-ERR] {}", line);
            }
        }
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(output.toString());
        List<String> unmatched = new java.util.ArrayList<>();
        if (node.has("unmatched") && node.get("unmatched").isArray()) {
            for (JsonNode u : node.get("unmatched")) {
                unmatched.add(u.asText());
            }
        }
        return new BotResult(
                node.path("ok").asBoolean(false),
                node.path("filledRows").asInt(0),
                unmatched,
                node.path("mode").asText(mode),
                node.path("message").asText(""));
    }
}
