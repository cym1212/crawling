package codeRecipe.crawling.crawling;

import codeRecipe.crawling.crawling.repository.ProductRepository;
import codeRecipe.crawling.crawling.repository.SalesLocationRepository;
import codeRecipe.crawling.crawling.repository.SalesRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DataProcessingService {

    private final ProductRepository productRepository;
    private final SalesLocationRepository salesLocationRepository;
    private final SalesRecordRepository salesRecordRepository;

    public String DataProcessing() {

        LocalDate date = LocalDate.now().minusDays(2);

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
}
