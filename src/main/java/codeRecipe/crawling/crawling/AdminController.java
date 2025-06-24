package codeRecipe.crawling.crawling;

import codeRecipe.crawling.crawling.domain.SalesLocation;
import codeRecipe.crawling.crawling.dto.SalesReportDto;
import codeRecipe.crawling.crawling.repository.SalesLocationRepository;
import codeRecipe.crawling.crawling.service.SalesReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final SalesReportService salesReportService;
    private final SalesLocationRepository salesLocationRepository;

    @GetMapping
    public String dashboard(Model model, HttpServletRequest request) {
        LocalDate today = LocalDate.now();
        
        // 오늘 매출 요약
        SalesReportDto.DailySummary todaySummary = salesReportService.getDailySummary(today);
        
        // 이번 달 매출 요약
        LocalDate monthStart = today.withDayOfMonth(1);
        SalesReportDto.MonthlySummary monthlySummary = salesReportService.getMonthlySummary(monthStart, today);
        
        // 업체별 이번 달 매출 Top 5
        List<SalesReportDto.LocationSales> topLocations = salesReportService.getTopLocationsByMonth(
            monthStart.getYear(), monthStart.getMonthValue(), 5);
        
        model.addAttribute("todaySummary", todaySummary);
        model.addAttribute("monthlySummary", monthlySummary);
        model.addAttribute("topLocations", topLocations);
        model.addAttribute("currentDate", today);
        model.addAttribute("currentUri", request.getRequestURI());
        
        return "admin/dashboard";
    }

    @GetMapping("/sales-report")
    public String salesReport(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) Long locationId,
            @RequestParam(defaultValue = "0") int page,
            Model model, HttpServletRequest request) {
        
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().withDayOfMonth(1);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();
        
        int pageSize = 30; // 30개씩 페이징
        SalesReportDto.PagedSalesData pagedSalesData;
        
        if (locationId != null) {
            pagedSalesData = salesReportService.getSalesByLocationAndDateRangeWithPaging(locationId, start, end, page, pageSize);
        } else {
            pagedSalesData = salesReportService.getSalesByDateRangeWithPaging(start, end, page, pageSize);
        }
        
        // 기간별 요약 정보
        SalesReportDto.PeriodSummary periodSummary = salesReportService.getPeriodSummary(start, end, locationId);
        
        // 업체 목록
        List<SalesLocation> locations = salesLocationRepository.findAll();
        
        model.addAttribute("pagedSalesData", pagedSalesData);
        model.addAttribute("periodSummary", periodSummary);
        model.addAttribute("locations", locations);
        model.addAttribute("startDate", start);
        model.addAttribute("endDate", end);
        model.addAttribute("selectedLocationId", locationId);
        model.addAttribute("currentUri", request.getRequestURI());
        
        return "admin/sales-report";
    }

    @GetMapping("/chart")
    public String chart(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) Long locationId,
            @RequestParam(defaultValue = "sales") String yAxisType,
            Model model, HttpServletRequest request) {
        
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(30);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();
        
        // 그래프 데이터 조회
        List<SalesReportDto.ChartData> chartData = salesReportService.getDailySalesChart(start, end, locationId);
        
        System.out.println("Chart data retrieved: " + chartData.size() + " items");
        
        // 테스트용: 데이터가 없으면 샘플 데이터 생성
        if (chartData == null || chartData.isEmpty()) {
            System.out.println("No chart data found, creating sample data");
            chartData = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                LocalDate date = start.plusDays(i);
                SalesReportDto.ChartData sample = SalesReportDto.ChartData.builder()
                    .date(date)
                    .dateString(date.toString())
                    .salesAmount((long)(Math.random() * 1000000 + 100000))
                    .actualSales((long)(Math.random() * 800000 + 80000))
                    .quantity((long)(Math.random() * 100 + 10))
                    .build();
                chartData.add(sample);
                System.out.println("Added sample data for " + date + ": sales=" + sample.getSalesAmount() + ", qty=" + sample.getQuantity());
            }
        }
        
        // 업체 목록
        List<SalesLocation> locations = salesLocationRepository.findAll();
        
        model.addAttribute("chartData", chartData);
        model.addAttribute("locations", locations);
        model.addAttribute("startDate", start);
        model.addAttribute("endDate", end);
        model.addAttribute("selectedLocationId", locationId);
        model.addAttribute("yAxisType", yAxisType);
        model.addAttribute("currentUri", request.getRequestURI());
        
        return "admin/chart";
    }

    @GetMapping("/sales-report/excel")
    public ResponseEntity<byte[]> downloadExcel(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) Long locationId) {
        
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().withDayOfMonth(1);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();
        
        byte[] excelData = salesReportService.generateExcelReport(start, end, locationId);
        
        String filename = String.format("sales_report_%s_%s.xlsx", 
            start.format(DateTimeFormatter.ofPattern("yyyyMMdd")),
            end.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", filename);
        
        return ResponseEntity.ok()
                .headers(headers)
                .body(excelData);
    }
}