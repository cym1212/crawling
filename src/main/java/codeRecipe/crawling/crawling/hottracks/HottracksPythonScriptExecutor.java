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

   // todo 기간별 데이터 수집할때는 수정해야함
   public LocalDate getTargetDate() {
       return LocalDate.now().minusDays(1);
//        return targetDate;
   }

   public void setTargetDate(LocalDate targetDate) {
       this.targetDate = targetDate; // targetDate를 원하는 날짜로 변경
   }
}



// package codeRecipe.crawling.crawling.hottracks;

// import codeRecipe.crawling.crawling.domain.Product;
// import codeRecipe.crawling.crawling.domain.SalesLocation;
// import codeRecipe.crawling.crawling.domain.SalesRecord;
// import codeRecipe.crawling.crawling.repository.ProductRepository;
// import codeRecipe.crawling.crawling.repository.SalesLocationRepository;
// import codeRecipe.crawling.crawling.repository.SalesRecordRepository;
// import com.fasterxml.jackson.databind.JsonNode;
// import com.fasterxml.jackson.databind.ObjectMapper;
// import lombok.Getter;
// import lombok.RequiredArgsConstructor;
// import lombok.extern.slf4j.Slf4j;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.core.io.ClassPathResource;
// import org.springframework.core.io.Resource;
// import org.springframework.core.io.ResourceLoader;
// import org.springframework.stereotype.Component;

// import java.io.*;
// import java.time.LocalDate;
// import java.util.Optional;

// @RequiredArgsConstructor
// @Getter
// @Component
// @Slf4j
// public class HottracksPythonScriptExecutor {

//     @Value("${app.login.hottracks-url}")
//     private String loginUrl;
//     @Value("${app.login.hottracks-username}")
//     private String username;
//     @Value("${app.login.hottracks-password}")
//     private String password;
//     @Value("${app.hottracks-target-url}")
//     private String targetUrl;

//     private final ResourceLoader resourceLoader;
//     private final ProductRepository productRepository;
//     private final SalesRecordRepository salesRecordRepository;
//     private final SalesLocationRepository salesLocationRepository;

//     private LocalDate targetDate = LocalDate.now().minusDays(1);

//     private String getPythonPath() {
//         // 여러 가능한 Python 경로를 시도
//         String[] possiblePaths = {
//             "python3",                 // 시스템 PATH의 python3
//             "python",                  // 시스템 PATH의 python
//             "/usr/bin/python3",        // 일반적인 Linux/macOS 경로
//             "/usr/local/bin/python3",  // Homebrew 등으로 설치된 경로
//             "/opt/homebrew/bin/python3", // M1 Mac Homebrew 경로
//             new File("venv/bin/python3").getAbsolutePath(), // 프로젝트 가상 환경
//             "/Library/Frameworks/Python.framework/Versions/3.13/bin/python3" // 원래 하드코딩된 경로
//         };
        
//         for (String path : possiblePaths) {
//             try {
//                 ProcessBuilder pb = new ProcessBuilder(path, "--version");
//                 Process process = pb.start();
//                 int exitCode = process.waitFor();
//                 if (exitCode == 0) {
//                     log.info("Python 경로 찾음: {}", path);
//                     return path;
//                 }
//             } catch (Exception e) {
//                 // 이 경로는 사용할 수 없음, 다음 경로 시도
//                 log.debug("Python 경로 시도 실패: {}", path);
//             }
//         }
        
//         // 기본값으로 "python3" 반환
//         log.warn("유효한 Python 경로를 찾을 수 없음, 기본값 'python3' 사용");
//         return "python3";
//     }

//     public String excutePythonScript() throws Exception {
//         // 1) 스크립트 임시 파일로 추출
//         String scriptName = "HottracksCrawler.py";
//         ClassPathResource resource = new ClassPathResource("scripts/" + scriptName);
//         File tempScript = File.createTempFile("script", ".py");
//         try (InputStream in = resource.getInputStream();
//              FileOutputStream out = new FileOutputStream(tempScript)) {
//             byte[] buf = new byte[1024];
//             int r;
//             while ((r = in.read(buf)) != -1) {
//                 out.write(buf, 0, r);
//             }
//         }

//         String startDate = getTargetDate().toString();
//         String endDate   = getTargetDate().toString();

//         // Python 경로 얻기
//         String pythonPath = getPythonPath();
//         log.info("사용할 Python 경로: {}", pythonPath);

//         ProcessBuilder pb = new ProcessBuilder(
//                 pythonPath,
//                 tempScript.getAbsolutePath(),
//                 loginUrl, targetUrl, username, password, startDate, endDate
//         );
//         // stderr/stdout 분리
//         pb.redirectErrorStream(false);
//         Process process = pb.start();

//         // stdout → JSON 버퍼
//         StringBuilder jsonBuf = new StringBuilder();
//         try (BufferedReader outR = new BufferedReader(new InputStreamReader(process.getInputStream()));
//              BufferedReader errR = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

//             String line;
//             while ((line = outR.readLine()) != null) {
//                 jsonBuf.append(line);
//             }
//             // stderr 로그로만 남기기
//             while ((line = errR.readLine()) != null) {
//                 log.error("[Python-ERR] {}", line);
//             }
//         }

//         process.waitFor();

//         String json = jsonBuf.toString();
//         log.info("Full JSON from Python: {}", json);

//         // 파싱 및 저장
//         parseAndSaveData(json);

//         // 부가 로그
//         System.out.println("핫트랙스 시간 = " + getTargetDate());
//         System.out.println("user.timezone: " + System.getProperty("user.timezone"));
//         System.out.println("현재 LocalDate = " + LocalDate.now());
//         System.out.println("현재 LocalDate - 1일 = " + LocalDate.now().minusDays(1));

//         return json;
//     }

//     public void parseAndSaveData(String jsonData) throws Exception {
//         ObjectMapper mapper = new ObjectMapper();
//         JsonNode root = mapper.readTree(jsonData);

//         for (JsonNode locNode : root) {
//             JsonNode rows = locNode.get("data").get("rows");
//             if (!"매출내역이 없습니다.".equals(rows.get(0).get(0).asText())) {
//                 String locationName = locNode.get("location").asText();
//                 SalesLocation salesLocation = salesLocationRepository
//                         .findByLocationNameAndRegion("Hottracks", locationName)
//                         .orElseGet(() -> salesLocationRepository.save(
//                                 SalesLocation.builder()
//                                         .locationName("Hottracks")
//                                         .region(locationName)
//                                         .build()
//                         ));

//                 for (JsonNode row : rows) {
//                     String productName = row.get(1).asText();
//                     String productCode = row.get(2).asText();
//                     Long quantity     = parseLongSafe(row.get(3).asText());
//                     Long salesAmount  = parseLongSafe(row.get(4).asText());
//                     Long actualSales  = parseLongSafe(row.get(5).asText());
//                     Long salesCost    = parseLongSafe(row.get(6).asText());

//                     Product product = Optional.ofNullable(productRepository.findByProductCode(productCode))
//                             .orElseGet(() -> productRepository.save(
//                                     Product.builder()
//                                             .productCode(productCode)
//                                             .productName(productName)
//                                             .build()
//                             ));

//                     // 제품명 변경 감지
// //                    if (!product.isSameProduct(productName, null)) {
// //                        product.setProductName(productName);
// //                        productRepository.save(product);
// //                    }

//                     // 중복 기록 검사
// //                    if (salesRecordRepository.findByProductAndSalesDateAndSalesLocation(
// //                            product, getTargetDate(), salesLocation) != null) {
// //                        log.info("Duplicate record for {} @ {} – skipping", productCode, locationName);
// //                        continue;
// //                    }

//                     // 새 레코드 저장
//                     salesRecordRepository.save(
//                             SalesRecord.builder()
//                                     .salesLocation(salesLocation)
//                                     .product(product)
//                                     .salesDate(getTargetDate())
//                                     .quantity(quantity)
//                                     .salesAmount(salesAmount)
//                                     .actualSales(actualSales)
//                                     .salesCost(salesCost)
//                                     .createdAt(LocalDate.now())
//                                     .build()
//                     );
//                 }
//             }
//         }
//     }

//     private Long parseLongSafe(String v) {
//         try {
//             return Long.parseLong(v.replace(",", "").trim());
//         } catch (NumberFormatException ex) {
//             log.warn("Invalid number format: {}", v);
//             return null;
//         }
//     }

//     public LocalDate getTargetDate() {
//         return targetDate;
//     }

//     public void setTargetDate(LocalDate date) {
//         this.targetDate = date;
//     }
// }