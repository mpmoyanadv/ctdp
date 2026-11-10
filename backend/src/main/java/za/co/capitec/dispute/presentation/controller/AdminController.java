package za.co.capitec.dispute.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import za.co.capitec.dispute.application.service.AuditService;
import za.co.capitec.dispute.application.service.AuthService;
import za.co.capitec.dispute.application.service.DisputeService;
import za.co.capitec.dispute.application.service.ProductivityService;
import za.co.capitec.dispute.infrastructure.cache.IdempotencyLockService;
import za.co.capitec.dispute.infrastructure.config.OpenApiConfig;
import za.co.capitec.dispute.infrastructure.messaging.KafkaEventService;
import za.co.capitec.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capitec.dispute.presentation.dto.ApiDtos.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "4. Supervisor Administration, Immutable Audit CSV & Productivity")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class AdminController {

    private final AuditService auditService;
    private final ProductivityService productivityService;
    private final AuthService authService;
    private final DisputeService disputeService;
    private final KafkaEventService kafkaEventService;
    private final IdempotencyLockService idempotencyLockService;

    public AdminController(
            AuditService auditService,
            ProductivityService productivityService,
            AuthService authService,
            DisputeService disputeService,
            KafkaEventService kafkaEventService,
            IdempotencyLockService idempotencyLockService) {
        this.auditService = auditService;
        this.productivityService = productivityService;
        this.authService = authService;
        this.disputeService = disputeService;
        this.kafkaEventService = kafkaEventService;
        this.idempotencyLockService = idempotencyLockService;
    }

    @Operation(
            summary = "Retrieve Staff Productivity & Turnaround Time Report",
            description = """
                    Calculates investigator turnaround metrics from `StaffWorkSession` timers (started when a dispute is claimed into `IN_REVIEW` and completed when resolved, rejected, escalated, or delegated externally).
                    """
    )
    @GetMapping("/reports/productivity")
    public ResponseEntity<ProductivityReportDto> getProductivityReport() {
        return ResponseEntity.ok(productivityService.buildProductivityReport());
    }

    @Operation(
            summary = "Query immutable security and lifecycle audit logs",
            description = "Returns chronological `AuditLog` entries, optionally filtered by `eventType` (e.g., `UNAUTHORIZED_ACCESS_ATTEMPT`, `ACCOUNT_LOCKED_FRAUD_REPORTED`)."
    )
    @GetMapping("/audit-logs")
    public ResponseEntity<List<AuditLogDto>> getAuditLogs(
            @Parameter(description = "Optional eventType filter (e.g. UNAUTHORIZED_ACCESS_ATTEMPT)")
            @RequestParam(value = "eventType", required = false) String eventType) {
        return ResponseEntity.ok(auditService.getAllLogs(eventType));
    }

    @Operation(
            summary = "Export immutable security & compliance audit trail as RFC-4180 CSV",
            description = "Streams the full immutable audit log as `capitec-audit-logs.csv` for regulatory/forensic inspection and logs `AUDIT_LOG_CSV_EXPORTED`."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CSV audit file generated and streamed")
    })
    @GetMapping("/audit-logs/export")
    public ResponseEntity<byte[]> exportAuditLogsCsv(
            @AuthenticationPrincipal AppUserPrincipal admin,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        auditService.logEvent(
                admin.getId(),
                admin.getEmail(),
                "INFO",
                "AUDIT_LOG_CSV_EXPORTED",
                "AuditLog",
                "ALL",
                ip,
                java.util.Map.of("exportedBy", admin.getEmail())
        );
        String csv = auditService.exportImmutableCsv();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"capitec-audit-logs.csv\"")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Operation(
            summary = "List all registered portal users and their fraud lock states",
            description = "Returns all Customer, Staff, Affiliate, and Admin user accounts along with `isLocked` and `lockedReason` status."
    )
    @GetMapping("/users")
    public ResponseEntity<List<UserProfileDto>> listUsers() {
        return ResponseEntity.ok(authService.listAllUsers());
    }

    @Operation(
            summary = "Administratively unlock a fraud-flagged user account",
            description = "Clears `is_locked = false` on a fraud-locked account and records `ACCOUNT_UNLOCKED_BY_ADMIN` in the immutable audit log."
    )
    @PostMapping("/users/{id}/unlock")
    public ResponseEntity<UserProfileDto> unlockUser(
            @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal admin,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.ok(authService.adminUnlockAccount(id, admin, ip));
    }

    @Operation(
            summary = "Supervisor assignment/reassignment of a dispute across domains, staff, or status",
            description = "Allows an `ADMIN` supervisor to reassign escalated or open disputes to specific internal investigators or external clearing partner domains (`PARTNER_BANKSERV`, `PARTNER_MERCHANT`) and update lifecycle status."
    )
    @PostMapping("/disputes/{id}/assign")
    public ResponseEntity<DisputeDetailDto> assignDispute(
            @PathVariable UUID id,
            @RequestBody AssignDisputeRequest request,
            @AuthenticationPrincipal AppUserPrincipal admin,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.ok(disputeService.adminAssignDispute(id, request, admin, ip));
    }

    @Operation(
            summary = "Inspect Apache Kafka event stream & Redis idempotency cache telemetry",
            description = """
                    Returns real-time telemetry for the event-driven architecture (`capitec.dispute.lifecycle.events`, `capitec.security.audit.events`, `capitec.notification.alert.events`), published/consumed message counters, and Redis distributed idempotency lock status.
                    """
    )
    @GetMapping("/events/kafka-stream")
    public ResponseEntity<KafkaStreamStatusDto> getKafkaEventStream() {
        return ResponseEntity.ok(kafkaEventService.getStreamStatus(
                idempotencyLockService.isRedisAvailable(),
                idempotencyLockService.getTtlSeconds()
        ));
    }
}
