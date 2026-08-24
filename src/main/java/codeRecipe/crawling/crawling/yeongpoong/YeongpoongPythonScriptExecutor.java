package codeRecipe.crawling.crawling.yeongpoong;


import codeRecipe.crawling.crawling.domain.Product;
import codeRecipe.crawling.crawling.domain.SalesLocation;
import codeRecipe.crawling.crawling.domain.SalesRecord;
import codeRecipe.crawling.crawling.repository.ProductRepository;
import codeRecipe.crawling.crawling.repository.SalesLocationRepository;
import codeRecipe.crawling.crawling.repository.SalesRecordRepository;
import codeRecipe.crawling.crawling.util.BarcodeNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.*;
import java.time.LocalDate;


@RequiredArgsConstructor
@Getter
@Component
@Slf4j
public class YeongpoongPythonScriptExecutor {

   @Value("${app.login.yeongpoong-url}")
   private String loginUrl;

   @Value("${app.login.yeongpoong-username}")
   private String username;

   @Value("${app.login.yeongpoong-password}")
   private String password;

   private final ResourceLoader resourceLoader;
   private final ProductRepository productRepository;
   private final SalesRecordRepository salesRecordRepository;
   private final SalesLocationRepository salesLocationRepository;

   LocalDate targetDate = null;

   private String getPythonPath() {
       return "/Library/Frameworks/Python.framework/Versions/3.13/bin/python3";
   }

   public String excutePythonScript() throws Exception {
       // 배치가 아닌 일반 호출 시 targetDate 초기화
       if (this.targetDate != null && !Thread.currentThread().getStackTrace()[2].getMethodName().contains("executeForDateRange")) {
           this.targetDate = null;
       }

       String pythonPath = new File("venv/bin/python3").getAbsolutePath();

       // JAR 내부의 Python 스크립트를 임시 파일로 추출
       String scriptName = "YeongpoongCrawler.py";
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

       String date = getTargetDate().toString();
       ProcessBuilder processBuilder = new ProcessBuilder(
               pythonPath, tempScriptFile1.getAbsolutePath(),
               loginUrl, username, password, date
       );

       // 핵심 수정: stderr와 stdout 분리
       processBuilder.redirectErrorStream(false);
       Process process = processBuilder.start();

       StringBuilder output = new StringBuilder();
       // stdout에서 JSON 데이터 읽기
       try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            // stderr는 별도로 읽어서 로그만 출력
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

            // stdout 처리
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }

            // stderr 처리 - 로그로만 출력
            while ((line = errorReader.readLine()) != null) {
                log.info("[Python-ERR] {}", line);
            }
        }

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String json = output.toString();
        log.info("Full JSON from Python: {}", json);

        parseAndSaveData(json);

        System.out.println("영풍문고 시간 = " + getTargetDate());
        System.out.println("user.timezone: " + System.getProperty("user.timezone"));
        System.out.println("현재 LocalDate = " + LocalDate.now());
        System.out.println("현재 LocalDate - 1일 = " + LocalDate.now().minusDays(1));
        return output.toString();
   }

   public void parseAndSaveData(String jsonData) throws Exception {
       ObjectMapper objectMapper = new ObjectMapper();
       JsonNode rootNode = objectMapper.readTree(jsonData);

       // Python 스크립트에서 에러를 반환한 경우 체크
       if (rootNode.has("error")) {
           String errorMessage = rootNode.get("error").asText();
           log.error("영풍문고 크롤링 실패: {}", errorMessage);
           throw new RuntimeException("영풍문고 크롤링 실패: " + errorMessage);
       }

       // locations 배열 가져오기
       JsonNode locationsNode = rootNode.get("locations");
       // data 배열 가져오기 (각 항목은 영풍문고 데이터 형식)
       JsonNode dataNode = rootNode.get("data");

       if (dataNode == null || !dataNode.isArray()) {
           log.error("영풍문고 응답 형식 오류: data 필드가 배열이 아닙니다");
           throw new RuntimeException("영풍문고 응답 형식 오류");
       }

       // 각 데이터 레코드 처리
       for (JsonNode record : dataNode) {
           // 영풍문고 데이터 필드 추출
           String wname = record.get("WNAME").asText();  // 지점명
           // 바코드(상품코드)는 30-we.com이 완전일치로 매칭하므로 저장 전 정규화한다.
           String barCode = BarcodeNormalizer.normalize(record.get("BAR_CD").asText());
           String productTitle = record.get("TTL").asText();  // 상품명
           String publisherName = record.get("PNAME").asText();  // 매입처명 (출판사로 사용)

           // 바코드가 없으면(빈 값) 매칭 불가·유령 레코드가 되므로 저장하지 않는다.
           if (barCode == null) {
               log.warn("영풍문고 바코드 없는 레코드 건너뜀: 지점={}, 상품={}", wname, productTitle);
               continue;
           }

           Long quantity = parseLongSafe(record.get("SALQTY").asText());  // 판매수량
           Long salesAmount = parseLongSafe(record.get("TOTAL").asText());  // 매출금액
           Long salesCost = parseLongSafe(record.get("SALAMT").asText());  // 순매출 → sales_cost에 저장
           Long salesPrice = parseLongSafe(record.get("NOR_PRC").asText());  // 평균판매단가 → sales_price에 저장
           // ADDTAX (부가세), DICAMT (할인금액)는 저장하지 않음

           // SalesLocation 처리
           SalesLocation salesLocation = salesLocationRepository.findByLocationNameAndRegion("Yeongpoong", wname)
                   .orElseGet(() -> {
                       SalesLocation newLocation = SalesLocation.builder()
                               .locationName("Yeongpoong")
                               .region(wname)
                               .build();
                       return salesLocationRepository.save(newLocation);
                   });

           // Product 처리
           Product product = productRepository.findByProductCode(barCode);
           if (product == null) {
               product = Product.builder()
                       .productCode(barCode)
                       .productName(productTitle)
                       .publisher(publisherName)
                       .build();
               product = productRepository.save(product);
           } else if (!product.isSameProduct(productTitle, publisherName)) {
               product = product.toBuilder()
                       .productName(productTitle)
                       .publisher(publisherName)
                       .build();
               productRepository.save(product);
           }

           // 중복 레코드 체크
           SalesRecord existingRecord = salesRecordRepository.findByProductAndSalesDateAndSalesLocation(
                   product, getTargetDate(), salesLocation
           );

           if (existingRecord != null) {
               log.info("Duplicate record found for product: {}, location: {}, date: {}. Skipping save.",
                       barCode, wname, getTargetDate());
               continue;
           }

           // 새 SalesRecord 저장
           SalesRecord newRecord = SalesRecord.builder()
                   .salesLocation(salesLocation)
                   .product(product)
                   .salesDate(getTargetDate())
                   .quantity(quantity)
                   .salesAmount(salesAmount)
                   .salesCost(salesCost)  // 순매출을 salesCost에 저장
                   .salesPrice(salesPrice)  // 평균판매단가를 salesPrice에 저장
                   .createdAt(LocalDate.now())
                   .build();
           salesRecordRepository.save(newRecord);
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
       return targetDate != null ? targetDate : LocalDate.now().minusDays(1);
   }

   public void setTargetDate(LocalDate targetDate) {
       this.targetDate = targetDate;
   }
}
