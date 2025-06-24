package codeRecipe.crawling.crawling.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

public class SalesReportDto {

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SalesData {
        private LocalDate salesDate;
        private String locationName;
        private String region;
        private String productName;
        private String productCode;
        private Long quantity;
        private Long salesAmount;
        private Long actualSales;
        private Long salesCost;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailySummary {
        private LocalDate date;
        private Long totalSalesAmount;
        private Long totalActualSales;
        private Long totalQuantity;
        private Long totalRecordCount;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlySummary {
        private int year;
        private int month;
        private Long totalSalesAmount;
        private Long totalActualSales;
        private Long totalQuantity;
        private Long totalRecordCount;
        private Long totalLocationCount;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeriodSummary {
        private LocalDate startDate;
        private LocalDate endDate;
        private Long totalSalesAmount;
        private Long totalActualSales;
        private Long totalQuantity;
        private Long totalRecordCount;
        private Long uniqueLocationCount;
        private Long uniqueProductCount;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocationSales {
        private String locationName;
        private String region;
        private Long totalSalesAmount;
        private Long totalActualSales;
        private Long totalQuantity;
        private Long recordCount;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductSales {
        private String productName;
        private String productCode;
        private Long totalSalesAmount;
        private Long totalActualSales;
        private Long totalQuantity;
        private Long recordCount;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DateSales {
        private LocalDate salesDate;
        private Long totalSalesAmount;
        private Long totalActualSales;
        private Long totalQuantity;
        private Long recordCount;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChartData {
        private LocalDate date;
        private String dateString;
        private Long salesAmount;
        private Long actualSales;
        private Long quantity;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PagedSalesData {
        private List<SalesData> content;
        private int currentPage;
        private int totalPages;
        private long totalElements;
        private boolean hasNext;
        private boolean hasPrevious;
    }
}