package codeRecipe.crawling.crawling.hottracks;

import codeRecipe.crawling.crawling.domain.HottracksPurchaseOrder;
import codeRecipe.crawling.crawling.repository.HottracksPurchaseOrderRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 30-we.com이 호출하는 교보 발주 내부 API.
 * [납품확인] 버튼 → 이 API → crawling 봇이 핫트랙스 납품확인 페이지에 수량 입력·저장.
 * 응답/오류 형식은 docs 의뢰서 계약과 일치. 쿠팡 CoupangInternalController 패턴.
 */
@Tag(name = "교보 발주 내부 API (30-we.com 연동용)",
        description = "30-we.com [납품확인] 버튼용. 명세서 수량을 핫트랙스 납품확인 페이지에 입력한다.")
@RestController
@RequestMapping("/internal/hottracks")
@RequiredArgsConstructor
@Slf4j
public class HottracksInternalController {

    private final HottracksDeliveryService deliveryService;
    private final HottracksInternalAuth internalAuth;
    private final HottracksPurchaseOrderRepository orderRepository;

    /** 납품등록 요청 본문. items: [{barcode, qty}], mode: TMPR|CMPLT(생략 시 CMPLT) */
    public record DeliverRequest(String plorRdpCode, String plorDate, String plorNum,
                                 List<Item> items, String mode) {
        public record Item(String barcode, int qty) {
        }
    }

    @Operation(summary = "납품등록 (납품량 입력 + 임시저장/납품확인)",
            description = "발주키(plorRdpCode/plorDate/plorNum)의 납품확인 페이지에 명세서 수량을 입력한다. "
                    + "mode=CMPLT면 납품확인까지(단, 서버의 allow-final-confirm=false면 임시저장으로 강등). "
                    + "오류: 401 unauthorized / 404 order_not_found / 409 invalid_state(이미 납품확인) / "
                    + "400 bad_request / 502 crawler_error.")
    @PostMapping("/orders/deliver")
    public ResponseEntity<Object> deliver(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody DeliverRequest request) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            List<HottracksDeliveryService.DeliverRequestItem> items = request.items() == null
                    ? List.of()
                    : request.items().stream()
                        .map(it -> new HottracksDeliveryService.DeliverRequestItem(it.barcode(), it.qty()))
                        .collect(Collectors.toList());
            String mode = request.mode() == null || request.mode().isBlank() ? "CMPLT" : request.mode();

            HottracksDeliveryService.DeliverResult result = deliveryService.deliver(
                    request.plorRdpCode(), request.plorDate(), request.plorNum(), items, mode);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().startsWith("발주를 찾을 수 없습니다")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            String error = status == HttpStatus.NOT_FOUND ? "order_not_found" : "bad_request";
            return ResponseEntity.status(status).body(Map.of("error", error,
                    "message", e.getMessage() == null ? "" : e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "invalid_state", "message", e.getMessage() == null ? "" : e.getMessage()));
        } catch (Exception e) {
            log.error("교보 납품등록 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "crawler_error", "message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    @Operation(summary = "발주 원본 엑셀 다운로드 (30-we [교보 엑셀] 버튼용)",
            description = "발주키의 핫트랙스 [엑셀출력] 원본 엑셀을 반환한다. "
                    + "오류: 401 unauthorized / 404 order_not_found(없는 발주키)·excel_not_found(엑셀 미저장).")
    @GetMapping("/orders/excel")
    public ResponseEntity<Object> orderExcel(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam String plorRdpCode,
            @RequestParam String plorDate,
            @RequestParam String plorNum) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        Optional<HottracksPurchaseOrder> orderOpt =
                orderRepository.findByPlorRdpCodeAndPlorDateAndPlorNum(plorRdpCode, plorDate, plorNum);
        if (orderOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "order_not_found"));
        }
        // 경로 주입 안전: 파라미터는 DB 조회 키로만 쓰고, 파일 경로는 DB에 저장된 excelPath만 사용(path traversal 불가).
        String path = orderOpt.get().getExcelPath();
        File file = (path == null || path.isBlank()) ? null : new File(path);
        if (file == null || !file.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "excel_not_found",
                    "message", "엑셀이 아직 저장되지 않았습니다. 다음 수집(매시 40분) 후 다시 시도하세요."));
        }
        // 실제 저장 파일 확장자에 맞춰 파일명·Content-Type 결정 (.xls 구형식도 지원)
        boolean isXls = file.getName().toLowerCase().endsWith(".xls");
        String ext = isXls ? ".xls" : ".xlsx";
        String contentType = isXls
                ? "application/vnd.ms-excel"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        String filename = plorRdpCode + "_" + plorDate + "_" + plorNum + ext;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(new FileSystemResource(file));
    }
}
