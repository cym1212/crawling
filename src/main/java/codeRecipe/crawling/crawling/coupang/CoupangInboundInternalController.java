package codeRecipe.crawling.crawling.coupang;

import codeRecipe.crawling.crawling.domain.InboundPlanStatus;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 로켓그로스 입고 내부 API — 30-we.com 연동용 (의뢰 #8, §8-7 계약).
 * ① 계획 생성 ② 송장 접수 ③ 회송지 설정
 * 문서(바코드/부착문서)는 사내 CDN(refrigerator) URL을 plan 행에 저장 — 30-we는 URL을 직접 링크한다 (교보 엑셀과 동일).
 */
@Tag(name = "3. 로켓그로스 입고 내부 API (30-we.com 연동용)")
@RestController
@RequestMapping("/internal/coupang/rg-inbound")
@RequiredArgsConstructor
@Slf4j
public class CoupangInboundInternalController {

    private final CoupangInboundPlanService planService;
    private final CoupangInternalAuth internalAuth;

    public record PlanItemRequest(Long vendorItemId, Integer quantity) { }

    public record PlanRequest(Integer boxCount, String requestedBy, List<PlanItemRequest> items) { }

    public record InvoiceRequest(Integer boxNo, String trackingNumber) { }

    public record InvoicesRequest(List<InvoiceRequest> invoices) { }

    public record SettingsRequest(String returnAddress) { }

    @PostMapping("/plans")
    public ResponseEntity<Object> createPlan(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody PlanRequest request) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            List<CoupangInboundPlanService.NewItem> items = request.items() == null ? List.of()
                    : request.items().stream()
                            .map(item -> new CoupangInboundPlanService.NewItem(item.vendorItemId(), item.quantity()))
                            .toList();
            Long planId = planService.createPlan(items,
                    request.boxCount() == null ? 0 : request.boxCount(), request.requestedBy());
            return ResponseEntity.ok(Map.of("planId", planId));
        } catch (CoupangInboundPlanService.ReturnAddressNotSetException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "return_address_not_set", "message", e.getMessage()));
        } catch (CoupangInboundPlanService.DuplicateInProgressException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "duplicate_in_progress", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "bad_request", "message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    @PostMapping("/plans/{planId}/invoices")
    public ResponseEntity<Object> registerInvoices(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable long planId,
            @RequestBody InvoicesRequest request) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            List<CoupangInboundPlanService.InvoiceEntry> invoices = request.invoices() == null ? List.of()
                    : request.invoices().stream()
                            .map(invoice -> new CoupangInboundPlanService.InvoiceEntry(
                                    invoice.boxNo(), invoice.trackingNumber()))
                            .toList();
            InboundPlanStatus status = planService.registerInvoices(planId, invoices);
            return ResponseEntity.ok(Map.of("planId", planId, "status", status.name()));
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().startsWith("계획을 찾을 수 없습니다")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            String error = status == HttpStatus.NOT_FOUND ? "plan_not_found" : "bad_request";
            return ResponseEntity.status(status).body(Map.of(
                    "error", error, "message", e.getMessage() == null ? "" : e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "invalid_state", "message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    @PutMapping("/settings")
    public ResponseEntity<Object> saveSettings(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody SettingsRequest request) {
        if (!internalAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            planService.saveReturnAddress(request.returnAddress());
            return ResponseEntity.ok(Map.of("saved", true));
        } catch (CoupangInboundPlanService.AddressNotInWingException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "address_not_in_wing", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "bad_request", "message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

}
