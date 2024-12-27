package codeRecipe.crawling.crawling.hottracks;


import codeRecipe.crawling.crawling.domain.Product;
import codeRecipe.crawling.crawling.domain.SalesLocation;
import codeRecipe.crawling.crawling.domain.SalesRecord;
import codeRecipe.crawling.crawling.repository.ProductRepository;
import codeRecipe.crawling.crawling.repository.SalesLocationRepository;
import codeRecipe.crawling.crawling.repository.SalesRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


@RequiredArgsConstructor
@Getter
//@Builder
@Component
@Slf4j
public class HottracksPythonScriptExecutor {

    @Value("${app.login.hottracks-url}")
    private String loginUrl;

    @Value("${app.login.hottracks-username}")
    private String username;

    @Value("${app.login.hottracks-password}")
    private String password;

    @Value("${app.hottracks-target-url}")
    private String targetUrl;


    private final ResourceLoader resourceLoader;
    private final ProductRepository productRepository;
    private final SalesRecordRepository salesRecordRepository;
    private final SalesLocationRepository salesLocationRepository;

    LocalDate targetDate = LocalDate.now().minusDays(1);

    private String getPythonPath() {
        return "/Library/Frameworks/Python.framework/Versions/3.13/bin/python3";
    }


    public String getScriptPath() throws IOException {
        Resource resource = resourceLoader.getResource("classpath:scripts/HottracksCrawler.py");
        return resource.getFile().getAbsolutePath(); // 실제 파일 경로 반환
    }


    public String excutePythonScript() throws Exception {

        String pythonPath = new File("venv/bin/python3").getAbsolutePath();

        // JAR 내부의 Python 스크립트를 임시 파일로 추출
        String scriptName = "HottracksCrawler.py";
        ClassPathResource resource = new ClassPathResource("scripts/" + scriptName);
        File tempScriptFile1 = File.createTempFile("script", ".py");
        try (InputStream inputStream = resource.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(tempScriptFile1)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }


        String startDate = getTargetDate().toString();
        String endDate = getTargetDate().toString();
        ProcessBuilder processBuilder = new ProcessBuilder(
                pythonPath, tempScriptFile1.getAbsolutePath(),
                loginUrl, targetUrl, username, password, startDate, endDate
        );

        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        parseAndSaveData(String.valueOf(output));

        System.out.println("핫트랙스 시간 = " + getTargetDate());
        System.out.println("user.timezone: " + System.getProperty("user.timezone"));
        System.out.println("현재 LocalDate = " + LocalDate.now());
        System.out.println("현재 LocalDate - 1일 = " + LocalDate.now().minusDays(1));
        return output.toString();
    }

    public void parseAndSaveData(String jsonData) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(jsonData);


        for (JsonNode locationNode : rootNode) {
            JsonNode rows = locationNode.get("data").get("rows");

            if (!"매출내역이 없습니다.".equals(rows.get(0).get(0).asText())) {
                String locationName = locationNode.get("location").asText();
                SalesLocation salesLocation = salesLocationRepository.findByLocationNameAndRegion("Hottracks", locationName).orElseGet(() -> {
                    SalesLocation newLocation = SalesLocation.builder()
                            .locationName("Hottracks")
                            .region(locationName)
                            .build();
                    return salesLocationRepository.save(newLocation);
                });

                for (JsonNode row : rows) {
                    String productName = row.get(1).asText();
                    String productCode = row.get(2).asText();
//                    Long quantity = Long.parseLong(row.get(3).asText().replace(",", "").trim());
//                    Long salesAmount = Long.parseLong(row.get(4).asText().replace(",", ""));
//                    Long actualSales = Long.parseLong(row.get(5).asText().replace(",", ""));
//                    Long salesCost = Long.parseLong(row.get(6).asText().replace(",", ""));
                    Long quantity = parseLongSafe(row.get(3).asText());
                    Long salesAmount = parseLongSafe(row.get(4).asText());
                    Long actualSales = parseLongSafe(row.get(5).asText());
                    Long salesCost = parseLongSafe(row.get(6).asText());
                    Product product = productRepository.findByProductCode(productCode);
                    if (product == null) {
                        product = Product.builder()
                                .productCode(productCode)
                                .productName(productName)
                                .build();
                        product = productRepository.save(product);
                    } else if (!product.isSameProduct(productName, null)) {
                        product = product.toBuilder()
                                .productName(productName)
                                .build();
                        productRepository.save(product);
                    }


                    SalesRecord existingRecord = salesRecordRepository.findByProductAndSalesDateAndSalesLocation(
                            product, getTargetDate(), salesLocation
                    );


                    if (existingRecord != null) {
                        log.info("Duplicate record found for product: {}, location: {}, date: {}. Skipping save.",
                                productCode, locationName, getTargetDate());
                        continue;
                    }


                    SalesRecord newRecord = SalesRecord.builder()
                            .salesLocation(salesLocation)
                            .product(product)
                            .salesDate(getTargetDate())
                            .quantity(quantity)
                            .salesAmount(salesAmount)
                            .actualSales(actualSales)
                            .salesCost(salesCost)
                            .createdAt(LocalDate.now())
                            .build();
                    salesRecordRepository.save(newRecord);

                }
            }
        }

    }

    private Long parseLongSafe(String value) {
        try {
            return Long.valueOf(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid number format for value: {}", value);
            return null;

        }
    }

    public LocalDate getTargetDate() {
        return LocalDate.now().minusDays(1);
    }
}