package codeRecipe.crawling.crawling.service;

import codeRecipe.crawling.crawling.domain.SalesRecord;
import codeRecipe.crawling.crawling.dto.SalesReportDto;
import codeRecipe.crawling.crawling.repository.SalesRecordRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SalesReportService {

    private final SalesRecordRepository salesRecordRepository;

    public SalesReportDto.DailySummary getDailySummary(LocalDate date) {
        List<Object[]> results = salesRecordRepository.findDailySummary(date);
        
        if (results.isEmpty()) {
            return SalesReportDto.DailySummary.builder()
                    .date(date)
                    .totalSalesAmount(0L)
                    .totalActualSales(0L)
                    .totalQuantity(0L)
                    .totalRecordCount(0L)
                    .build();
        }
        
        Object[] result = results.get(0);
        return SalesReportDto.DailySummary.builder()
                .date(date)
                .totalSalesAmount(result[1] != null ? ((Number) result[1]).longValue() : 0L)
                .totalActualSales(result[2] != null ? ((Number) result[2]).longValue() : 0L)
                .totalQuantity(result[3] != null ? ((Number) result[3]).longValue() : 0L)
                .totalRecordCount(result[4] != null ? ((Number) result[4]).longValue() : 0L)
                .build();
    }

    public SalesReportDto.MonthlySummary getMonthlySummary(LocalDate startDate, LocalDate endDate) {
        int year = startDate.getYear();
        int month = startDate.getMonthValue();
        
        List<Object[]> results = salesRecordRepository.findMonthlySummary(year, month);
        
        if (results.isEmpty()) {
            return SalesReportDto.MonthlySummary.builder()
                    .year(year)
                    .month(month)
                    .totalSalesAmount(0L)
                    .totalActualSales(0L)
                    .totalQuantity(0L)
                    .totalRecordCount(0L)
                    .totalLocationCount(0L)
                    .build();
        }
        
        Object[] result = results.get(0);
        return SalesReportDto.MonthlySummary.builder()
                .year(((Number) result[0]).intValue())
                .month(((Number) result[1]).intValue())
                .totalSalesAmount(result[2] != null ? ((Number) result[2]).longValue() : 0L)
                .totalActualSales(result[3] != null ? ((Number) result[3]).longValue() : 0L)
                .totalQuantity(result[4] != null ? ((Number) result[4]).longValue() : 0L)
                .totalRecordCount(result[5] != null ? ((Number) result[5]).longValue() : 0L)
                .totalLocationCount(result[6] != null ? ((Number) result[6]).longValue() : 0L)
                .build();
    }

    public SalesReportDto.PeriodSummary getPeriodSummary(LocalDate startDate, LocalDate endDate, Long locationId) {
        List<Object[]> results = salesRecordRepository.findPeriodSummary(startDate, endDate, locationId);
        
        if (results.isEmpty()) {
            return SalesReportDto.PeriodSummary.builder()
                    .startDate(startDate)
                    .endDate(endDate)
                    .totalSalesAmount(0L)
                    .totalActualSales(0L)
                    .totalQuantity(0L)
                    .totalRecordCount(0L)
                    .uniqueLocationCount(0L)
                    .uniqueProductCount(0L)
                    .build();
        }
        
        Object[] result = results.get(0);
        return SalesReportDto.PeriodSummary.builder()
                .startDate(startDate)
                .endDate(endDate)
                .totalSalesAmount(result[0] != null ? ((Number) result[0]).longValue() : 0L)
                .totalActualSales(result[1] != null ? ((Number) result[1]).longValue() : 0L)
                .totalQuantity(result[2] != null ? ((Number) result[2]).longValue() : 0L)
                .totalRecordCount(result[3] != null ? ((Number) result[3]).longValue() : 0L)
                .uniqueLocationCount(result[4] != null ? ((Number) result[4]).longValue() : 0L)
                .uniqueProductCount(result[5] != null ? ((Number) result[5]).longValue() : 0L)
                .build();
    }

    public List<SalesReportDto.LocationSales> getTopLocationsByMonth(int year, int month, int limit) {
        List<Object[]> results = salesRecordRepository.findTopLocationsByMonth(year, month, limit);
        
        return results.stream()
                .map(result -> SalesReportDto.LocationSales.builder()
                        .locationName((String) result[0])
                        .region((String) result[1])
                        .totalSalesAmount(result[2] != null ? ((Number) result[2]).longValue() : 0L)
                        .totalActualSales(result[3] != null ? ((Number) result[3]).longValue() : 0L)
                        .totalQuantity(result[4] != null ? ((Number) result[4]).longValue() : 0L)
                        .recordCount(result[5] != null ? ((Number) result[5]).longValue() : 0L)
                        .build())
                .collect(Collectors.toList());
    }

    public List<SalesReportDto.SalesData> getSalesByDateRange(LocalDate startDate, LocalDate endDate) {
        List<SalesRecord> records = salesRecordRepository.findSalesDataByDateRange(startDate, endDate);
        
        return records.stream()
                .map(this::convertToSalesData)
                .collect(Collectors.toList());
    }

    public List<SalesReportDto.SalesData> getSalesByLocationAndDateRange(Long locationId, LocalDate startDate, LocalDate endDate) {
        List<SalesRecord> records = salesRecordRepository.findSalesDataByLocationAndDateRange(locationId, startDate, endDate);
        
        return records.stream()
                .map(this::convertToSalesData)
                .collect(Collectors.toList());
    }

    // 페이징된 매출 데이터 조회
    public SalesReportDto.PagedSalesData getSalesByDateRangeWithPaging(LocalDate startDate, LocalDate endDate, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<SalesRecord> pageResult = salesRecordRepository.findSalesDataByDateRangeWithPaging(startDate, endDate, pageable);
        
        List<SalesReportDto.SalesData> content = pageResult.getContent().stream()
                .map(this::convertToSalesData)
                .collect(Collectors.toList());
        
        return SalesReportDto.PagedSalesData.builder()
                .content(content)
                .currentPage(pageResult.getNumber())
                .totalPages(pageResult.getTotalPages())
                .totalElements(pageResult.getTotalElements())
                .hasNext(pageResult.hasNext())
                .hasPrevious(pageResult.hasPrevious())
                .build();
    }

    public SalesReportDto.PagedSalesData getSalesByLocationAndDateRangeWithPaging(Long locationId, LocalDate startDate, LocalDate endDate, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<SalesRecord> pageResult = salesRecordRepository.findSalesDataByLocationAndDateRangeWithPaging(locationId, startDate, endDate, pageable);
        
        List<SalesReportDto.SalesData> content = pageResult.getContent().stream()
                .map(this::convertToSalesData)
                .collect(Collectors.toList());
        
        return SalesReportDto.PagedSalesData.builder()
                .content(content)
                .currentPage(pageResult.getNumber())
                .totalPages(pageResult.getTotalPages())
                .totalElements(pageResult.getTotalElements())
                .hasNext(pageResult.hasNext())
                .hasPrevious(pageResult.hasPrevious())
                .build();
    }

    // 그래프용 일별 매출 데이터
    public List<SalesReportDto.ChartData> getDailySalesChart(LocalDate startDate, LocalDate endDate, Long locationId) {
        List<Object[]> results = salesRecordRepository.findDailySalesChart(startDate, endDate, locationId);
        
        return results.stream()
                .map(result -> SalesReportDto.ChartData.builder()
                        .date((LocalDate) result[0])
                        .salesAmount(result[1] != null ? ((Number) result[1]).longValue() : 0L)
                        .actualSales(result[2] != null ? ((Number) result[2]).longValue() : 0L)
                        .quantity(result[3] != null ? ((Number) result[3]).longValue() : 0L)
                        .build())
                .collect(Collectors.toList());
    }

    private SalesReportDto.SalesData convertToSalesData(SalesRecord record) {
        return SalesReportDto.SalesData.builder()
                .salesDate(record.getSalesDate())
                .locationName(record.getSalesLocation().getLocationName())
                .region(record.getSalesLocation().getRegion())
                .productName(record.getProduct().getProductName())
                .productCode(record.getProduct().getProductCode())
                .quantity(record.getQuantity())
                .salesAmount(record.getSalesAmount())
                .actualSales(record.getActualSales())
                .salesCost(record.getSalesCost())
                .build();
    }

    public byte[] generateExcelReport(LocalDate startDate, LocalDate endDate, Long locationId) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            
            // 시트 생성
            Sheet sheet = workbook.createSheet("매출 보고서");
            
            // 헤더 스타일
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            
            // 헤더 생성
            Row headerRow = sheet.createRow(0);
            String[] headers = {"판매일", "업체명", "지역", "상품명", "상품코드", "수량", "매출금액", "실매출", "매출원가"};
            
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }
            
            // 데이터 조회 및 추가
            List<SalesReportDto.SalesData> salesData;
            if (locationId != null) {
                salesData = getSalesByLocationAndDateRange(locationId, startDate, endDate);
            } else {
                salesData = getSalesByDateRange(startDate, endDate);
            }
            
            int rowNum = 1;
            for (SalesReportDto.SalesData data : salesData) {
                Row row = sheet.createRow(rowNum++);
                
                row.createCell(0).setCellValue(data.getSalesDate().toString());
                row.createCell(1).setCellValue(data.getLocationName());
                row.createCell(2).setCellValue(data.getRegion());
                row.createCell(3).setCellValue(data.getProductName());
                row.createCell(4).setCellValue(data.getProductCode());
                row.createCell(5).setCellValue(data.getQuantity() != null ? data.getQuantity() : 0);
                row.createCell(6).setCellValue(data.getSalesAmount() != null ? data.getSalesAmount() : 0);
                row.createCell(7).setCellValue(data.getActualSales() != null ? data.getActualSales() : 0);
                row.createCell(8).setCellValue(data.getSalesCost() != null ? data.getSalesCost() : 0);
            }
            
            // 컬럼 너비 자동 조정
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            
            workbook.write(outputStream);
            return outputStream.toByteArray();
            
        } catch (IOException e) {
            throw new RuntimeException("엑셀 파일 생성 중 오류가 발생했습니다.", e);
        }
    }
}