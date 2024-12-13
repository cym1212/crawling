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

    LocalDate targetDate = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).minusDays(1).toLocalDate();

    private String getPythonPath() {
        return "/Library/Frameworks/Python.framework/Versions/3.13/bin/python3";
    }



    public String getScriptPath() throws IOException {
        Resource resource = resourceLoader.getResource("classpath:scripts/HottracksCrawler.py");
        return resource.getFile().getAbsolutePath(); // 실제 파일 경로 반환
    }



    public String  excutePythonScript() throws Exception {

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


        String startDate = targetDate.toString();
        String endDate = targetDate.toString();
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


        return output.toString();
    }

    public void parseAndSaveData(String jsonData) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(jsonData);

//        List<SalesLocation> salesLocations = new ArrayList<>();
//        List<Product> products = new ArrayList<>();
//        List<SalesRecord> salesRecords = new ArrayList<>();

        for (JsonNode locationNode : rootNode) {
            String locationName = locationNode.get("location").asText();
            SalesLocation salesLocation = salesLocationRepository.findByLocationNameAndRegion("Hottracks", locationName).orElseGet(() -> {
                SalesLocation newLocation = SalesLocation.builder()
                        .locationName("Hottracks")
                        .region(locationName)
                        .build();
                return salesLocationRepository.save(newLocation);
            });
//            SalesLocation salesLocation = SalesLocation.builder()
//                    .locationName("hottracks")
//                    .region(locationName)
//                    .build();
//            salesLocations.add(salesLocation);

            JsonNode rows = locationNode.get("data").get("rows");
            if (rows.size() > 0 && !"매출내역이 없습니다.".equals(rows.get(0).get(0).asText())){
                for (JsonNode row : rows) {
                    String productName = row.get(1).asText();
                    String productCode = row.get(2).asText();
                    Long quantity = Long.parseLong(row.get(3).asText());
                    Long salesAmount = Long.parseLong(row.get(4).asText().replace(",", ""));
                    Long actualSales = Long.parseLong(row.get(5).asText().replace(",", ""));
                    Long salesCost = Long.parseLong(row.get(6).asText().replace(",", ""));

                    Product product = productRepository.findByProductCode(productCode);
//                    Product product = Product.builder()
//                            .productCode(productCode)
//                            .productName(productName)
//                            .build();
//                    products.add(product);
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

                    SalesRecord existingRecord = salesRecordRepository.findTopBySalesLocationAndProductAndSalesDate(
                            salesLocation, product, targetDate
                    );


                    if (existingRecord == null || !existingRecord.isSameSalesRecord(quantity, null, null, null, salesAmount)) {
                        SalesRecord newRecord = SalesRecord.builder()
                                .salesLocation(salesLocation)
                                .product(product)
                                .salesDate(targetDate)
                                .quantity(quantity)
                                .salesAmount(salesAmount)
                                .actualSales(actualSales)
                                .salesCost(salesCost)
                                .build();
                        salesRecordRepository.save(newRecord);
                    }
                }
            }
        }
//        productRepository.saveAll(products);
//        salesLocationRepository.saveAll(salesLocations);
//        salesRecordRepository.saveAll(salesRecords);
    }


}
