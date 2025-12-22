package codeRecipe.crawling.crawling;

import codeRecipe.crawling.crawling.repository.SalesRecordRepository;
import lombok.RequiredArgsConstructor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DataProcessingService {


    private final SalesRecordRepository salesRecordRepository;

    public String dailyDataProcessing() {

        LocalDate date = LocalDate.now().minusDays(1);

        Long totalCount = salesRecordRepository.getTotalQuantityByDate(date);
        Long totalAmount = salesRecordRepository.getTotalSalesAmountByDate(date);

//        Object[] data1 = salesRecordRepository.getTotalQuantityAndSalesAmountByDate(date);
//        Long totalCount = (Long) data1[0];
//        Long totalAmount =(Long) data1[1];

        List<Object[]> data2 = salesRecordRepository.findSalesSummaryByDate(date);
        List<Object[]> data3 = salesRecordRepository.findSalesSummaryByLocationAndDate(date);

        StringBuilder message = new StringBuilder();
        message.append("# ").append(date).append("일자 서점 매출 내역\n").append("전체 : ").append(totalCount).append("건 = ").append(totalAmount).append("원\n\n\n").append("## 상품별\n\n");

        for (Object[] result : data2) {
            String productName = (String) result[0];
            String productCode = (String) result[1];
            Long totalQuantity = (Long) result[2];
            Long totalSalesAmount = (Long) result[3];

            message.append(String.format("%s (%s) - %d건 = %, d원\n", productName, productCode, totalQuantity, totalSalesAmount));
        }

        message.append("\n\n## 서점별 \n\n");
        for (Object[] result : data3) {
            String locationName = (String) result[0];
            String region = (String) result[1];
            Long totalQuantity = (Long) result[2];
            Long totalSalesAmount = (Long) result[3];
            message.append(String.format("%s (%s) - %d건 = %, d원\n", locationName, region, totalQuantity, totalSalesAmount));
        }

        return message.toString();
    }

    // 테스트용 텍스트 형식 (읽기 쉬운 형태)
    public String weeklyDataProcessingText() {
        LocalDate today = LocalDate.now();
        LocalDate lastMonday = today.minusWeeks(1).with(DayOfWeek.MONDAY);
        LocalDate lastSunday = lastMonday.plusDays(6);

        Long totalCount = salesRecordRepository.getTotalQuantityByDateRange(lastMonday, lastSunday);
        Long totalAmount = salesRecordRepository.getTotalSalesAmountByDateRange(lastMonday, lastSunday);

        List<Object[]> data2 = salesRecordRepository.findSalesSummaryByDateRange(lastMonday, lastSunday);
        List<Object[]> data3 = salesRecordRepository.findSalesSummaryByLocationAndDateRange(lastMonday, lastSunday);

        StringBuilder message = new StringBuilder();
        message.append("# ").append(lastMonday).append(" ~ ").append(lastSunday).append(" 주간 서점 매출 내역\n\n")
                .append("전체 : ").append(totalCount).append("건 = ").append(String.format("%,d", totalAmount)).append("원\n\n")
                .append("## 상품별\n\n");

        for (Object[] result : data2) {
            String productName = (String) result[0];
            String productCode = (String) result[1];
            Long totalQuantity = (Long) result[2];
            Long totalSalesAmount = (Long) result[3];

            message.append(String.format("%s (%s) - %d건 = %,d원\n", productName, productCode, totalQuantity, totalSalesAmount));
        }

        message.append("\n\n## 서점별\n\n");
        for (Object[] result : data3) {
            String locationName = (String) result[0];
            String region = (String) result[1];
            Long totalQuantity = (Long) result[2];
            Long totalSalesAmount = (Long) result[3];
            message.append(String.format("%s (%s) - %d건 = %,d원\n", locationName, region, totalQuantity, totalSalesAmount));
        }

        return message.toString();
    }

    // 슬랙용 JSON 형식
    public String weeklyDataProcessing() {
        // 현재 날짜 기준으로 지난주 월요일과 일요일 계산
        LocalDate today = LocalDate.now();
        LocalDate lastMonday = today.minusWeeks(1).with(DayOfWeek.MONDAY);
        LocalDate lastSunday = lastMonday.plusDays(6);

        // 주간 데이터 집계
        Long totalCount = salesRecordRepository.getTotalQuantityByDateRange(lastMonday, lastSunday);
        Long totalAmount = salesRecordRepository.getTotalSalesAmountByDateRange(lastMonday, lastSunday);

        List<Object[]> productData = salesRecordRepository.findSalesSummaryByDateRange(lastMonday, lastSunday);
        List<Object[]> storeData = salesRecordRepository.findSalesSummaryByLocationAndDateRange(lastMonday, lastSunday);

        // JSON 메시지 생성
        JSONObject slackMessage = new JSONObject();
        JSONArray blocks = new JSONArray();

        // 헤더
        blocks.put(new JSONObject()
                .put("type", "header")
                .put("text", new JSONObject()
                        .put("type", "plain_text")
                        .put("text", "📊 주간 서점 매출 내역")
                        .put("emoji", true)
                )
        );

        // 날짜 범위 및 전체 매출
        blocks.put(new JSONObject()
                .put("type", "section")
                .put("fields", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "mrkdwn")
                                .put("text", "*📅 날짜 범위:*\n" + lastMonday + " ~ " + lastSunday)
                        )
                        .put(new JSONObject()
                                .put("type", "mrkdwn")
                                .put("text", "*💰 전체 매출:*\n" + totalCount + "건 = ₩" + String.format("%,d", totalAmount))
                        )
                )
        );

        // 구분선
        blocks.put(new JSONObject().put("type", "divider"));

        // 상품별 매출
        blocks.put(new JSONObject()
                .put("type", "section")
                .put("text", new JSONObject()
                        .put("type", "mrkdwn")
                        .put("text", ":gem: *상품별 매출*")
                )
        );

        // 상품별 매출 나누기
        JSONArray productFields = new JSONArray();
        for (int i = 0; i < productData.size(); i++) {
            if (i > 0 && i % 10 == 0) {
                // 10개씩 새로운 섹션 추가
                blocks.put(new JSONObject()
                        .put("type", "section")
                        .put("fields", productFields)
                );
                productFields = new JSONArray(); // 새로운 배열 시작
            }

            Object[] product = productData.get(i);
            String productName = (String) product[0];
            String productCode = (String) product[1];
            Long totalQuantity = (Long) product[2];
            Long totalSalesAmount = (Long) product[3];

            productFields.put(new JSONObject()
                    .put("type", "mrkdwn")
                    .put("text", productName + " (" + productCode + ")\n" + totalQuantity + "건 = ₩" + String.format("%,d", totalSalesAmount))
            );
        }

        // 남은 필드 추가
        if (!productFields.isEmpty()) {
            blocks.put(new JSONObject()
                    .put("type", "section")
                    .put("fields", productFields)
            );
        }

        // 구분선
        blocks.put(new JSONObject().put("type", "divider"));

        // 서점별 매출
        blocks.put(new JSONObject()
                .put("type", "section")
                .put("text", new JSONObject()
                        .put("type", "mrkdwn")
                        .put("text", ":office: *서점별 매출*")
                )
        );

        // 서점별 매출 나누기
        JSONArray storeFields = new JSONArray();
        for (int i = 0; i < storeData.size(); i++) {
            if (i > 0 && i % 10 == 0) {
                // 10개씩 새로운 섹션 추가
                blocks.put(new JSONObject()
                        .put("type", "section")
                        .put("fields", storeFields)
                );
                storeFields = new JSONArray(); // 새로운 배열 시작
            }

            Object[] store = storeData.get(i);
            String locationName = (String) store[0];
            String region = (String) store[1];
            Long totalQuantity = (Long) store[2];
            Long totalSalesAmount = (Long) store[3];

            storeFields.put(new JSONObject()
                    .put("type", "mrkdwn")
                    .put("text", locationName + " (" + region + ")\n" + totalQuantity + "건 = ₩" + String.format("%,d", totalSalesAmount))
            );
        }

        // 남은 필드 추가
        if (!storeFields.isEmpty()) {
            blocks.put(new JSONObject()
                    .put("type", "section")
                    .put("fields", storeFields)
            );
        }

        // 구분선
        blocks.put(new JSONObject().put("type", "divider"));

        // 블록을 메시지에 추가
        slackMessage.put("blocks", blocks);

        // JSON 메시지 반환
        return slackMessage.toString();
    }
}
