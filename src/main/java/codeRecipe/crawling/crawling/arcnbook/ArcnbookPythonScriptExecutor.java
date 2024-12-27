package codeRecipe.crawling.crawling.arcnbook;


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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;


@RequiredArgsConstructor
@Getter
//@Builder
@Component
@Slf4j
public class ArcnbookPythonScriptExecutor {

    @Value("${app.login.arcnbook-url}")
    private String loginUrl;

    @Value("${app.login.arcnbook-username}")
    private String username;

    @Value("${app.login.arcnbook-password}")
    private String password;


    private final ResourceLoader resourceLoader;
    private final ProductRepository productRepository;
    private final SalesRecordRepository salesRecordRepository;
    private final SalesLocationRepository salesLocationRepository;

    private static final Logger logger = LoggerFactory.getLogger(ArcnbookPythonScriptExecutor.class);
    ZonedDateTime nowInSeoul = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
    LocalDate targetDate = LocalDate.now().minusDays(1);


    String[] ArcnbookRegion = {"수지점", "신촌점", "롯데월드몰점", "동탄호수점", "월계점", "부산아시아드점", "몬드리안점", "광안리점", "아크앤북온라인", "충청점", "부산명지점", "세종점"};
    String locationName = "Arcnbook";


    private String getPythonPath() {
        return "/Library/Frameworks/Python.framework/Versions/3.13/bin/python3";
    }

    public String getScriptPath() throws IOException {
        Resource resource = resourceLoader.getResource("classpath:scripts/ArcnbookCrawler.py");
        return resource.getFile().getAbsolutePath(); // 실제 파일 경로 반환
    }

    public String excutePythonScript() throws Exception {

        String pythonPath = new File("venv/bin/python3").getAbsolutePath();
        String scriptName = "ArcnbookCrawler.py";

        // JAR 내부 스크립트를 임시 파일로 추출
        ClassPathResource resource = new ClassPathResource("scripts/" + scriptName);
        File tempScriptFile = File.createTempFile("script", ".py");
        try (InputStream inputStream = resource.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(tempScriptFile)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
                pythonPath, tempScriptFile.getAbsolutePath(),
                loginUrl, username, password
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

        process.waitFor();

        String rawData = output.toString();
        logger.info("Raw response from Python script: {}", rawData);


        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(rawData);
        } catch (Exception e) {
            logger.error("Failed to parse JSON response: {}", rawData, e);
            throw new RuntimeException("Error parsing JSON response", e);
        }

        List<List<String>> parsedData = new ArrayList<>();
        for (JsonNode arrayNode : rootNode) {
            List<String> row = new ArrayList<>();
            for (JsonNode item : arrayNode) {
                row.add(item.asText());
            }
            parsedData.add(row);
        }
//        saveSalesLocations();
        parseAndSaveData(parsedData);

        System.out.println("아크앤 북 시간 (메서드로 호출) = " + getTargetDate());
        System.out.println("user.timezone: " + System.getProperty("user.timezone"));
        System.out.println("현재 LocalDate = " + LocalDate.now());
        System.out.println("현재 LocalDate - 1일 = " + LocalDate.now().minusDays(1));


        return rawData;
    }


    public void parseAndSaveData(List<List<String>> jsonData) throws Exception {


        for (List<String> row : jsonData) {

            if (row.size() > 1) {
                String productCode = row.get(0);
                String productName = row.get(1);
                String publisher = row.get(2);
//                Long salesPrice = Long.valueOf(row.get(3).replace(",","").trim());
//                Long quantity = Long.valueOf(row.get(5).replace(",", "").trim());
//                Long salesAmount = Long.valueOf(row.get(6).replace(",","").trim());
                Long salesPrice = parseLongSafe(row.get(3));

                Product product = productRepository.findByProductCode(productCode);
                if (product == null) {
                    product = Product.builder()
                            .productCode(productCode)
                            .productName(productName)
                            .publisher(publisher)
                            .build();
                    product = productRepository.save(product);
                } else if (!product.isSameProduct(productName, publisher)) {
                    product = product.toBuilder()
                            .productName(productName)
                            .publisher(publisher)
                            .build();
                    productRepository.save(product);
                }


                for (int i = 7; i + 1 < row.size(); i += 2) {
                    String regionQuantityStr = row.get(i).replace(",", "").trim();
                    String regionSalesAmountStr = row.get(i + 1).replace(",", "").trim();


                    // 추가된 유효성 검사
                    if (!regionQuantityStr.matches("-?\\d+") || !regionSalesAmountStr.matches("-?\\d+")) {
                        throw new IllegalArgumentException("Invalid number format: " + regionQuantityStr + ", " + regionSalesAmountStr);
                    }

                    Long regionQuantity = Long.parseLong(regionQuantityStr);
                    Long regionSalesAmount = Long.parseLong(regionSalesAmountStr);

                    if (regionQuantity == 0 || regionSalesAmount == 0) {
                        continue;
                    }

                    int regionIndex = (i - 7) / 2;
                    if (regionIndex >= ArcnbookRegion.length) break;
                    String regionName = ArcnbookRegion[regionIndex];


                    SalesLocation salesLocation = salesLocationRepository.findByLocationNameAndRegion("Arcnbook", regionName)
                            .orElseGet(() -> salesLocationRepository.save(
                                    SalesLocation.builder()
                                            .locationName("Arcnbook")
                                            .region(regionName)
                                            .build()
                            ));

                    SalesRecord existingRecord = salesRecordRepository.findByProductAndSalesDateAndSalesLocation(
                            product, getTargetDate(), salesLocation
                    );


                    if (existingRecord != null) {
                        log.info("Duplicate record found for product: {}, location: {}, date: {}. Skipping save.",
                                productCode, regionName, getTargetDate());
                        continue;
                    }

                    SalesRecord newRecord = SalesRecord.builder()
                            .salesLocation(salesLocation)
                            .product(product)
                            .salesPrice(salesPrice)
                            .salesDate(getTargetDate())
                            .quantity(regionQuantity)
                            .salesAmount(regionSalesAmount)
                            .createdAt(LocalDate.now())
                            .build();

                    salesRecordRepository.save(newRecord);

                }
            }
        }
    }

    private void saveSalesLocations() {
        for (String region : ArcnbookRegion) {
            boolean exists = salesLocationRepository.existsByLocationNameAndRegion(locationName, region);
            if (!exists) {
                SalesLocation location = SalesLocation.builder()
                        .locationName(locationName)
                        .region(region) // 지역 이름
                        .build();

                salesLocationRepository.save(location);
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