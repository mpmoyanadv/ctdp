package za.co.capi.dispute.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import za.co.capi.dispute.application.service.AuditService;
import za.co.capi.dispute.application.service.DisputeService;
import za.co.capi.dispute.domain.model.DisputeAttachment;
import za.co.capi.dispute.domain.model.DisputeCategory;
import za.co.capi.dispute.domain.model.DisputeStatus;
import za.co.capi.dispute.infrastructure.config.OpenApiConfig;
import za.co.capi.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capi.dispute.presentation.dto.ApiDtos.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/disputes")
@Tag(name = "3. Transaction Disputes, Historic View, Evidence & State Machine")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class DisputeController {

    private final DisputeService disputeService;

    public DisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @Operation(
            summary = "List active and historic disputed transactions scoped by role and domain",
            description = """
                    Returns all active (`LOGGED`, `IN_REVIEW`, `PENDING_CUSTOMER_ACTION`, `PENDING_EXTERNAL_REVIEW`, `ESCALATED`) and historic/closed (`RESOLVED`, `REFUNDED`, `REJECTED`) disputes visible to the caller:
                    - **`CUSTOMER`:** Only their own active and historic disputed transactions.
                    - **`STAFF` / `ADMIN`:** Full enterprise queue with unmasked customer details.
                    - **`AFFILIATE` (`PARTNER_BANKSERV` / `PARTNER_MERCHANT`):** Only disputes explicitly delegated to their partner domain, with **POPIA PII masking** applied (`SA ID: 850101****082`, `Account: ******0341`, `Phone: +2782****192`).
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Scoped list of active and historic disputes returned")
    })
    @GetMapping
    public ResponseEntity<PageResponseDto<DisputeSummaryDto>> listDisputes(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(value = "status", required = false) DisputeStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(disputeService.listDisputesForActor(principal, status, page, size));
    }

    @Operation(
            summary = "Retrieve full dispute details, notes, SHA-256 attachments, and linked lineage (IDOR protected)",
            description = """
                    Fetches the complete dispute aggregate including timeline notes, uploaded evidence documents with SHA-256 checksums, and prior case lineage (`original_dispute_id`).
                    - **Strict IDOR Boundary Enforcement:** If Customer A requests Customer B's dispute UUID (or an external Affiliate requests a non-delegated dispute), the request is denied with **`403 Forbidden`** and an immutable `HIGH`-severity `UNAUTHORIZED_ACCESS_ATTEMPT` audit record is committed in an independent `REQUIRES_NEW` transaction.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dispute aggregate returned (with POPIA masking if caller is AFFILIATE)"),
            @ApiResponse(responseCode = "403", description = "403 Forbidden — Cross-customer or cross-domain IDOR boundary violation logged"),
            @ApiResponse(responseCode = "404", description = "Dispute UUID not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<DisputeDetailDto> getDispute(
            @Parameter(description = "Target dispute UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.ok(disputeService.getDisputeDetail(id, principal, ip));
    }

    @Operation(
            summary = "Submit a new transaction dispute (JSON payload with Redis Idempotency Lock)",
            description = """
                    Creates a new transaction dispute in `LOGGED` status for the authenticated customer and publishes a `DISPUTE_CREATED` event to Apache Kafka (`capi.dispute.lifecycle.events`).
                    - **Redis Distributed Idempotency Lock:** Acquires an atomic `SET NX EX` lock in Redis using `X-Idempotency-Key` (or a SHA-256 request fingerprint) to prevent duplicate dispute submissions during network retries or double clicks (`409 Conflict`).
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Dispute created in LOGGED status"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "403", description = "Only CUSTOMER role or owner of the transaction may log a dispute"),
            @ApiResponse(responseCode = "409", description = "409 Conflict — Duplicate dispute submission prevented by Redis idempotency lock")
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DisputeDetailDto> createDisputeJson(
            @Parameter(description = "Optional client-generated idempotency key to prevent duplicate submissions on network retry")
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateDisputeRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(disputeService.createDispute(request, List.of(), principal, ip, idempotencyKey));
    }

    @Operation(
            summary = "Submit a new transaction dispute with multi-document evidence (Multipart Form Data + Redis Idempotency Lock)",
            description = """
                    Creates a new transaction dispute (`LOGGED`), atomically stores up to **5 supporting evidence documents** (`PDF`, `PNG`, `JPG`, max **5MB** per file) with **SHA-256** checksums, acquires a **Redis idempotency lock** (`SET NX EX`), and emits a `DISPUTE_CREATED` event to Apache Kafka (`capi.dispute.lifecycle.events`).
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Dispute and SHA-256 verified evidence files persisted"),
            @ApiResponse(responseCode = "400", description = "Exceeded 5-file limit, file > 5MB, or unsupported MIME type"),
            @ApiResponse(responseCode = "409", description = "409 Conflict — Duplicate dispute submission prevented by Redis idempotency lock")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DisputeDetailDto> createDisputeMultipart(
            @Parameter(description = "Optional client-generated idempotency key to prevent duplicate submissions on network retry")
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(value = "transactionId", required = false) UUID transactionId,
            @RequestParam(value = "merchantName", required = false) String merchantName,
            @RequestParam(value = "paymentRail", required = false) String paymentRail,
            @RequestParam("category") DisputeCategory category,
            @RequestParam("amount") BigDecimal amount,
            @RequestParam("reason") String reason,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        CreateDisputeRequest req = new CreateDisputeRequest(transactionId, merchantName, paymentRail, category, amount, reason);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(disputeService.createDispute(req, files, principal, ip, idempotencyKey));
    }

    @Operation(
            summary = "Upload additional supporting evidence documents to an existing dispute",
            description = """
                    Appends supporting `PDF`, `PNG`, or `JPG` files (max 5 files total per dispute, max 5MB each) and records their **SHA-256** checksums.
                    - **Terminal State Lock:** Rejects uploads with `400 Bad Request` if the dispute is in a terminal state (`RESOLVED`, `REFUNDED`, `REJECTED`).
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Evidence files stored with SHA-256 checksums"),
            @ApiResponse(responseCode = "400", description = "Dispute is in a terminal state or file constraints violated"),
            @ApiResponse(responseCode = "403", description = "IDOR boundary violation")
    })
    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<AttachmentDto>> uploadAttachments(
            @PathVariable UUID id,
            @RequestParam("files") List<MultipartFile> files,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(disputeService.uploadAttachments(id, files, principal, ip));
    }

    @Operation(
            summary = "Download an authorized dispute evidence attachment",
            description = "Streams the binary evidence document after enforcing customer ownership and partner domain boundary checks."
    )
    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<Resource> downloadAttachment(
            @PathVariable UUID id,
            @PathVariable UUID attachmentId,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        DisputeAttachment attachment = disputeService.getAuthorizedAttachment(id, attachmentId, principal, ip);
        Resource resource = disputeService.loadAttachmentFile(attachment);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + attachment.getFileName() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }

    @Operation(
            summary = "Claim a dispute for internal investigation and start work-session timer",
            description = """
                    Assigns the dispute to the authenticated Capi `STAFF` or `ADMIN` investigator, transitions status to `IN_REVIEW`, and opens an active `StaffWorkSession` timer to track investigator turnaround time.
                    """
    )
    @PostMapping("/{id}/claim")
    public ResponseEntity<DisputeDetailDto> claimDispute(
            @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.ok(disputeService.claimDispute(id, principal, ip));
    }

    @Operation(
            summary = "Add an investigation note or request customer evidence (`PENDING_CUSTOMER_ACTION`)",
            description = """
                    Appends a note to a non-terminal dispute:
                    - If a `STAFF`/`ADMIN` sets `requiresCustomerReply = true`, the dispute automatically transitions to `PENDING_CUSTOMER_ACTION`.
                    - When the `CUSTOMER` replies with a note or new evidence on a `PENDING_CUSTOMER_ACTION` dispute, status returns to `IN_REVIEW`.
                    - Denied with `400 Bad Request` if the dispute is in a terminal state (`RESOLVED`, `REFUNDED`, `REJECTED`).
                    """
    )
    @PostMapping("/{id}/notes")
    public ResponseEntity<DisputeDetailDto> addNote(
            @PathVariable UUID id,
            @Valid @RequestBody AddNoteRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.ok(disputeService.addNote(id, request, principal, ip));
    }

    @Operation(
            summary = "Escalate dispute to Supervisor (`ESCALATED`)",
            description = """
                    Can be triggered by `CUSTOMER`, `STAFF`, or `ADMIN` on any non-terminal dispute. Transitions status to `ESCALATED`, closes and records duration on any active `StaffWorkSession`, unassigns the current investigator, and routes the case to the Supervisor (`ADMIN`) queue.
                    """
    )
    @PostMapping("/{id}/escalate")
    public ResponseEntity<DisputeDetailDto> escalateDispute(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> payload,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        String reason = payload != null ? payload.get("reason") : null;
        return ResponseEntity.ok(disputeService.escalateDispute(id, reason, principal, ip));
    }

    @Operation(
            summary = "Delegate dispute to an external clearing or merchant partner (`PARTNER_BANKSERV` / `PARTNER_MERCHANT`)",
            description = """
                    Delegates a dispute (such as a PayShap instant interbank proxy transfer) to an external partner domain (`PARTNER_BANKSERV` / **BankservAfrica** or `PARTNER_MERCHANT`), transitions status to `PENDING_EXTERNAL_REVIEW`, closes the internal investigator's `StaffWorkSession`, and enforces automatic POPIA PII masking for external affiliate viewers.
                    """
    )
    @PostMapping("/{id}/delegate")
    public ResponseEntity<DisputeDetailDto> delegateToAffiliate(
            @PathVariable UUID id,
            @Valid @RequestBody DelegateDisputeRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.ok(disputeService.delegateToAffiliate(id, request, principal, ip));
    }

    @Operation(
            summary = "Transition dispute status (`PATCH`)",
            description = "Updates dispute status (`IN_REVIEW`, `PENDING_CUSTOMER_ACTION`, `RESOLVED`, `REFUNDED`, `REJECTED`). Transitioning to a terminal state sets `resolvedAt` and completes any open `StaffWorkSession`."
    )
    @PatchMapping("/{id}/status")
    public ResponseEntity<DisputeDetailDto> patchStatus(
            @PathVariable UUID id,
            @Valid @RequestBody StatusUpdateRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.ok(disputeService.updateStatus(id, request, principal, ip));
    }

    @Operation(
            summary = "Transition dispute status (`POST`)",
            description = "Updates dispute status (`IN_REVIEW`, `PENDING_CUSTOMER_ACTION`, `RESOLVED`, `REFUNDED`, `REJECTED`). Transitioning to a terminal state sets `resolvedAt` and completes any open `StaffWorkSession`."
    )
    @PostMapping("/{id}/status")
    public ResponseEntity<DisputeDetailDto> postStatus(
            @PathVariable UUID id,
            @Valid @RequestBody StatusUpdateRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.ok(disputeService.updateStatus(id, request, principal, ip));
    }

    @Operation(
            summary = "Re-open a REJECTED dispute by filing a linked follow-up dispute (`original_dispute_id`)",
            description = """
                    Allows a `CUSTOMER` whose dispute was `REJECTED` to file a new linked dispute (`LOGGED`) that preserves a self-referential lineage pointer (`original_dispute_id`) to the prior rejected case and surfaces all prior notes and rejection history to investigators.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Linked dispute created with original_dispute_id lineage"),
            @ApiResponse(responseCode = "400", description = "Only REJECTED disputes can be re-opened via a linked dispute")
    })
    @PostMapping("/{id}/reopen")
    public ResponseEntity<DisputeDetailDto> reopenRejectedDispute(
            @PathVariable UUID id,
            @Valid @RequestBody ReopenDisputeRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(disputeService.reopenRejectedDispute(id, request, principal, ip));
    }
}
