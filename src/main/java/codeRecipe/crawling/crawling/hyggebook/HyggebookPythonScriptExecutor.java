package codeRecipe.crawling.crawling.hyggebook;


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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;


@RequiredArgsConstructor
@Getter
//@Builder
@Component
@Slf4j
public class HyggebookPythonScriptExecutor {

    @Value("${app.login.hygge-url}")
    private String loginUrl;

    @Value("${app.login.hygge-username}")
    private String username;

    @Value("${app.login.hygge-password}")
    private String password;


    private final ResourceLoader resourceLoader;
    private final ProductRepository productRepository;
    private final SalesRecordRepository salesRecordRepository;
    private final SalesLocationRepository salesLocationRepository;

    private static final Logger logger = LoggerFactory.getLogger(HyggebookPythonScriptExecutor.class);
    ZonedDateTime nowInSeoul = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
    LocalDate targetDate = LocalDate.now().minusDays(1);



    String locationName = "Hyggebook";


    private String getPythonPath() {
        return "/Library/Frameworks/Python.framework/Versions/3.13/bin/python3";
    }

    public String getScriptPath() throws IOException {
        Resource resource = resourceLoader.getResource("classpath:scripts/HyggebookCrawler.py");
        return resource.getFile().getAbsolutePath(); // 실제 파일 경로 반환
    }

    public String excutePythonScript() throws Exception {

        String pythonPath = new File("venv/bin/python3").getAbsolutePath();
        String scriptName = "HyggebookCrawler.py";

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

        String startDate = getTargetDate().toString();
        String endDate = getTargetDate().toString();

        ProcessBuilder processBuilder = new ProcessBuilder(
                pythonPath, tempScriptFile.getAbsolutePath(),
                loginUrl, username, password, startDate, endDate
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

        List<String> locations = new ArrayList<>();
        for (JsonNode locationNode : rootNode.get("locations")) {
            locations.add(locationNode.asText());
        }

        findNewLocations(locations);

        String[] HyggebookRegion =  new String[locations.size()/2];
        for (int i = 0, j = 0; i < locations.size(); i += 2, j++) {
            HyggebookRegion[j] = locations.get(i); // 배열의 j번째 인덱스에 값 추가
        }

        List<List<String>> parsedData = new ArrayList<>();
        for (JsonNode arrayNode : rootNode.get("data")) {
            List<String> row = new ArrayList<>();
            for (JsonNode item : arrayNode) {
                row.add(item.asText());
            }
            parsedData.add(row);
        }
//        saveSalesLocations();
        parseAndSaveData(parsedData, HyggebookRegion);

        System.out.println("아크앤 북 시간 (메서드로 호출) = " + getTargetDate());
        System.out.println("user.timezone: " + System.getProperty("user.timezone"));
        System.out.println("현재 LocalDate = " + LocalDate.now());
        System.out.println("현재 LocalDate - 1일 = " + LocalDate.now().minusDays(1));


        return rawData;
    }

    private void findNewLocations(List<String> locations) {
        for (String region : locations) {
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


    public void parseAndSaveData(List<List<String>> jsonData,String[] HyggebookRegion) throws Exception {


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
                    if (regionIndex >= HyggebookRegion.length) break;
                    String regionName = HyggebookRegion[regionIndex];


                    SalesLocation salesLocation = salesLocationRepository.findByLocationNameAndRegion("Hyggebook", regionName)
                            .orElseGet(() -> salesLocationRepository.save(
                                    SalesLocation.builder()
                                            .locationName("Hyggebook")
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



    private Long parseLongSafe(String value) {
        try {
            return Long.valueOf(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid number format for value: {}", value);
            return null;

        }
    }
    // todo 기간별 데이터 수집할때는 수정해야함
    public LocalDate getTargetDate() {
        return LocalDate.now().minusDays(1);
//        return targetDate;
    }
    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate; // targetDate를 원하는 날짜로 변경
    }
}