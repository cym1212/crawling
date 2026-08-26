package codeRecipe.crawling.crawling.coupang;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 로켓그로스 입고 — RPA 에이전트(담당자 PC) 연동 API (의뢰 #8).
 * 에이전트는 X-Agent-Token으로 인증하고, 작업을 폴링해 WING을 조작한 뒤 결과를 보고한다.
 */
@Tag(name = "4. 로켓그로스 입고 에이전트 API (담당자 PC RPA용)")
@RestController
@RequestMapping("/internal/coupang/rg-inbound/agent")
@RequiredArgsConstructor
@Slf4j
public class CoupangInboundAgentController {

    private final CoupangInboundAgentService agentService;
    private final CoupangAgentAuth agentAuth;

    public record SubmitResultRequest(boolean success, String wingInboundId, String fulfillmentCenter,
                                      String arrivalDate, String failReason) { }

    public record InvoiceResultRequest(boolean success, String failReason) { }

    public record AddressSyncRequest(List<String> addresses) { }

    @GetMapping("/jobs")
    public ResponseEntity<Object> jobs(
            @RequestHeader(value = "X-Agent-Token", required = false) String token) {
        if (!agentAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        return ResponseEntity.ok(Map.of("jobs", agentService.pendingJobs()));
    }

    @PostMapping("/plans/{planId}/start")
    public ResponseEntity<Object> start(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @PathVariable long planId) {
        if (!agentAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            agentService.start(planId);
            return ResponseEntity.ok(Map.of("planId", planId, "status", "RPA_RUNNING"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "plan_not_found", "message", e.getMessage() == null ? "" : e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "invalid_state", "message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    @PostMapping("/plans/{planId}/submit-result")
    public ResponseEntity<Object> submitResult(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @PathVariable long planId,
            @RequestBody SubmitResultRequest request) {
        if (!agentAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            agentService.submitResult(planId, request.success(), request.wingInboundId(),
                    request.fulfillmentCenter(), request.arrivalDate(), request.failReason());
            return ResponseEntity.ok(Map.of("planId", planId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "plan_not_found", "message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    @PostMapping("/plans/{planId}/invoice-result")
    public ResponseEntity<Object> invoiceResult(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @PathVariable long planId,
            @RequestBody InvoiceResultRequest request) {
        if (!agentAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            agentService.invoiceResult(planId, request.success(), request.failReason());
            return ResponseEntity.ok(Map.of("planId", planId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "plan_not_found", "message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    @PostMapping("/plans/{planId}/documents/{type}")
    public ResponseEntity<Object> uploadDocument(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @PathVariable long planId,
            @PathVariable String type,
            @RequestParam("file") MultipartFile file) {
        if (!agentAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            agentService.saveDocument(planId, type, file);
            return ResponseEntity.ok(Map.of("planId", planId, "type", type));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "bad_request", "message", e.getMessage() == null ? "" : e.getMessage()));
        } catch (Exception e) {
            log.error("입고 문서 저장 실패 planId={} type={}", planId, type, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "storage_error", "message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    @PostMapping("/addresses")
    public ResponseEntity<Object> syncAddresses(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestBody AddressSyncRequest request) {
        if (!agentAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        int count = agentService.syncAddresses(request.addresses());
        return ResponseEntity.ok(Map.of("synced", count));
    }
}
