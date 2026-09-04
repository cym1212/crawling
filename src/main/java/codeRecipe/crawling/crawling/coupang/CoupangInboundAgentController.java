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
import org.springframework.web.bind.annotation.RestController;

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
                                      String arrivalDate, String failReason,
                                      String barcodePdfUrl, String attachPdfUrl) { }

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
                    request.fulfillmentCenter(), request.arrivalDate(), request.failReason(),
                    request.barcodePdfUrl(), request.attachPdfUrl());
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

    public record DocumentUrlRequest(String url) { }

    /** 문서 CDN URL 후속 보고 — 에이전트가 refrigerator 업로드 후 URL만 전달한다 (파일 전송 없음) */
    @PostMapping("/plans/{planId}/documents/{type}")
    public ResponseEntity<Object> reportDocument(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @PathVariable long planId,
            @PathVariable String type,
            @RequestBody DocumentUrlRequest request) {
        if (!agentAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        try {
            agentService.saveDocumentUrl(planId, type, request.url());
            return ResponseEntity.ok(Map.of("planId", planId, "type", type));
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().startsWith("계획을 찾을 수 없습니다")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            String error = status == HttpStatus.NOT_FOUND ? "plan_not_found" : "bad_request";
            return ResponseEntity.status(status).body(Map.of(
                    "error", error, "message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    public record NotifyRequest(String message) { }

    @PostMapping("/notify")
    public ResponseEntity<Object> notify(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestBody NotifyRequest request) {
        if (!agentAuth.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        agentService.notifyFromAgent(request.message());
        return ResponseEntity.ok(Map.of("sent", true));
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
