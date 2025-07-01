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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        
        // 판매처별 그래프 데이터 조회
        Map<String, List<SalesReportDto.ChartData>> chartDataByLocation = salesReportService.getDailySalesChartByLocation(start, end, locationId);
        
        System.out.println("Chart data by location retrieved: " + chartDataByLocation.size() + " locations");
        
        // 테스트용: 데이터가 없으면 샘플 데이터 생성
        if (chartDataByLocation == null || chartDataByLocation.isEmpty()) {
            System.out.println("No chart data found, creating sample data");
            chartDataByLocation = new HashMap<>();
            List<SalesLocation> sampleLocations = salesLocationRepository.findAll();
            
            if (sampleLocations.isEmpty()) {
                // 샘플 판매처 생성 (지점별로 구분)
                sampleLocations = Arrays.asList(
                    SalesLocation.builder().locationName("Hotracks").region("건대스타시티점").build(),
                    SalesLocation.builder().locationName("Hotracks").region("수유점").build(),
                    SalesLocation.builder().locationName("교보문고").region("강남점").build(),
                    SalesLocation.builder().locationName("교보문고").region("잠실점").build()
                );
            }
            
            // locationId가 선택된 경우 해당 지점만 필터링
            if (locationId != null) {
                // 실제 DB에서 선택된 locationId의 정보를 조회
                SalesLocation selectedLocation = salesLocationRepository.findById(locationId).orElse(null);
                if (selectedLocation != null) {
                    sampleLocations = Arrays.asList(selectedLocation);
                } else {
                    // DB에 해당 locationId가 없으면 빈 리스트
                    sampleLocations = new ArrayList<>();
                }
            }
            
            for (int locationIndex = 0; locationIndex < sampleLocations.size(); locationIndex++) {
                SalesLocation location = sampleLocations.get(locationIndex);
                List<SalesReportDto.ChartData> locationData = new ArrayList<>();
                // 지점별로 구분하여 키 생성 (판매처명 + 지점명)
                String locationKey = location.getLocationName() + " " + (location.getRegion() != null ? location.getRegion() : "");
                int locationHash = Math.abs(locationKey.hashCode()) % 1000;
                
                for (int i = 0; i < 7; i++) {
                    LocalDate date = start.plusDays(i);
                    // 지점명의 해시코드를 사용하여 각 지점별로 고유한 시드값 생성
                    long baseSeed = locationHash * 1000 + i;
                    SalesReportDto.ChartData sample = SalesReportDto.ChartData.builder()
                        .date(date)
                        .dateString(date.toString())
                        .salesAmount((long)(baseSeed % 300000 + 100000 + locationHash * 1000))
                        .actualSales((long)(baseSeed % 250000 + 80000 + locationHash * 800))
                        .quantity((long)(baseSeed % 30 + 10 + locationHash % 20))
                        .build();
                    locationData.add(sample);
                }
                chartDataByLocation.put(locationKey, locationData);
            }
        }
        
        // 업체 목록
        List<SalesLocation> locations = salesLocationRepository.findAll();
        
        model.addAttribute("chartDataByLocation", chartDataByLocation);
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
    
    @GetMapping("/roulette")
    public String roulette(Model model, HttpServletRequest request) {
        model.addAttribute("currentUri", request.getRequestURI());
        return "admin/roulette";
    }
}