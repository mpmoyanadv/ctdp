package za.co.capitec.dispute.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import za.co.capitec.dispute.domain.model.DisputeCategory;
import za.co.capitec.dispute.domain.model.DisputeStatus;
import za.co.capitec.dispute.domain.model.UserDomain;
import za.co.capitec.dispute.domain.model.UserRole;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiDtos {

    private ApiDtos() {
    }

    @Schema(description = "Credentials payload for stateless JWT authentication across Customer, Staff, Affiliate, and Admin portals.")
    public record LoginRequest(
            @Schema(description = "Registered user email address", example = "customer@capitec.test")
            @NotBlank(message = "Email is required")
            @Email(message = "Valid email is required")
            String email,

            @Schema(description = "User plaintext password verified against BCrypt digest", example = "Password123!")
            @NotBlank(message = "Password is required")
            String password
    ) {}

    @Schema(description = "Authenticated user profile, security role, institutional domain, and fraud lock status.")
    public record UserProfileDto(
            @Schema(description = "Unique user identifier") UUID id,
            @Schema(description = "User email address", example = "customer@capitec.test") String email,
            @Schema(description = "Full legal name", example = "Thabo Mokoena") String fullName,
            @Schema(description = "RBAC security role (`CUSTOMER`, `STAFF`, `AFFILIATE`, `ADMIN`)") UserRole role,
            @Schema(description = "Organizational boundary domain (`CUSTOMER`, `INTERNAL_CAPITEC`, `PARTNER_BANKSERV`, `PARTNER_MERCHANT`)") UserDomain userDomain,
            @Schema(description = "External clearing/merchant partner institution name when applicable", example = "BankservAfrica") String partnerInstitution,
            @Schema(description = "True if the account is locked due to customer-reported login fraud") boolean isLocked,
            @Schema(description = "Audit reason recorded when the account was locked") String lockedReason
    ) {}

    @Schema(description = "Stateless JWT authentication token and user profile returned on successful login.")
    public record AuthResponse(
            @Schema(description = "Signed HMAC-SHA256 JWT access token embedding role, domain, and tokenVersion") String accessToken,
            @Schema(description = "Token type scheme", example = "Bearer") String tokenType,
            @Schema(description = "Token validity window in seconds", example = "28800") long expiresInSeconds,
            @Schema(description = "Authenticated principal profile") UserProfileDto user
    ) {}

    @Schema(description = "Confirmation payload returned after executing emergency fraud lockdown and OTP dispatch.")
    public record FraudReportResponse(
            @Schema(description = "Indicates whether the account was locked and active JWTs revoked", example = "true") boolean locked,
            @Schema(description = "Email address of the secured account", example = "customer@capitec.test") String email,
            @Schema(description = "Confirmation message detailing 6-digit OTP dispatch") String message
    ) {}

    @Schema(description = "Payload to verify the 6-digit One-Time PIN (OTP), set a new password, and unlock a fraud-flagged account.")
    public record ResetPasswordVerifyRequest(
            @Schema(description = "Locked customer account email", example = "customer@capitec.test")
            @NotBlank(message = "Email is required")
            @Email(message = "Valid email is required")
            String email,

            @Schema(description = "6-digit numeric OTP code dispatched via email", example = "482910")
            @NotBlank(message = "6-digit OTP code is required")
            @Size(min = 6, max = 6, message = "OTP must be 6 digits")
            String otpCode,

            @Schema(description = "New password to replace compromised credentials", example = "NewPassword123!")
            @NotBlank(message = "New password is required")
            @Size(min = 8, message = "New password must be at least 8 characters")
            String newPassword
    ) {}

    @Schema(description = "Settled retail banking transaction across domestic payment rails (PayShap, DebiCheck/Debit Order, Card POS, Online E-Commerce).")
    public record TransactionDto(
            @Schema(description = "Unique transaction UUID") UUID id,
            @Schema(description = "Immutable ledger transaction reference", example = "TXN-2026-9003") String transactionReference,
            @Schema(description = "Counterparty merchant or payment description", example = "PayShap Transfer") String merchantName,
            @Schema(description = "Domestic settlement rail (`PAYSHAP`, `DEBIT_ORDER`, `CARD_POS`, `ONLINE_ECOMMERCE`)", example = "PAYSHAP") String paymentRail,
            @Schema(description = "Settled transaction amount in ZAR", example = "1200.00") BigDecimal amount,
            @Schema(description = "ISO 4217 currency code", example = "ZAR") String currency,
            @Schema(description = "Last 4 digits of card/mandate or payment rail code", example = "SHAP") String cardLastFour,
            @Schema(description = "Geographic or clearing channel location", example = "Instant Interbank Rail") String location,
            @Schema(description = "UTC timestamp when transaction settled on the ledger") Instant transactedAt
    ) {}

    @Schema(description = "Request payload to initiate a formal transaction dispute against a settled ledger entry.")
    public record CreateDisputeRequest(
            @Schema(description = "Optional UUID of the disputed ledger transaction") UUID transactionId,
            @Schema(description = "Merchant or counterparty name", example = "Checkers Sixty60") String merchantName,
            @Schema(description = "Settlement payment rail", example = "ONLINE_ECOMMERCE") String paymentRail,
            @Schema(description = "Standardized banking dispute reason classification", example = "UNAUTHORIZED_TRANSACTION")
            @NotNull(message = "Dispute category is required")
            DisputeCategory category,
            @Schema(description = "Disputed monetary amount in ZAR", example = "450.00")
            @NotNull(message = "Dispute amount is required")
            @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
            BigDecimal amount,
            @Schema(description = "Customer's formal statement explaining the dispute", example = "Unauthorized online card debit not initiated by me.")
            @NotBlank(message = "Dispute reason is required")
            @Size(min = 5, max = 2000, message = "Reason must be between 5 and 2000 characters")
            String reason
    ) {}

    @Schema(description = "Request payload to re-open a previously REJECTED dispute by filing a new linked case (`original_dispute_id`).")
    public record ReopenDisputeRequest(
            @Schema(description = "Optional updated dispute category") DisputeCategory category,
            @Schema(description = "Additional customer justification or new evidence summary for the linked dispute",
                    example = "Re-opening with SAPS sworn affidavit and merchant cancellation confirmation.")
            @NotBlank(message = "Updated explanation is required to file a linked dispute")
            @Size(min = 5, max = 2000)
            String reason
    ) {}

    @Schema(description = "Investigation or customer communication note appended to an active dispute.")
    public record AddNoteRequest(
            @Schema(description = "Note content", example = "Please upload police affidavit")
            @NotBlank(message = "Note message is required")
            @Size(max = 3000)
            String message,
            @Schema(description = "If true, note is restricted to internal Capitec staff/admin and hidden from Customer UI") boolean internalOnly,
            @Schema(description = "If true, transitions dispute status to `PENDING_CUSTOMER_ACTION` awaiting customer response") boolean requiresCustomerReply
    ) {}

    @Schema(description = "State machine transition request (`IN_REVIEW`, `PENDING_CUSTOMER_ACTION`, `RESOLVED`, `REFUNDED`, `REJECTED`).")
    public record StatusUpdateRequest(
            @Schema(description = "Target dispute lifecycle state", example = "REFUNDED")
            @NotNull(message = "Target status is required")
            DisputeStatus status,
            @Schema(description = "Formal resolution or rejection explanation recorded on the case audit trail",
                    example = "Verified duplicate DebiCheck mandate presentation; full reversal credited.")
            String resolutionSummary
    ) {}

    @Schema(description = "Request to delegate a dispute to an external clearing partner or merchant domain (e.g., BankservAfrica for PayShap).")
    public record DelegateDisputeRequest(
            @Schema(description = "Target external partner domain (`PARTNER_BANKSERV` or `PARTNER_MERCHANT`)", example = "PARTNER_BANKSERV")
            @NotNull(message = "External partner domain is required")
            UserDomain partnerDomain,
            @Schema(description = "Interbank or merchant handoff instructions", example = "Delegating PayShap proxy trace to BankservAfrica clearing team.")
            String note
    ) {}

    @Schema(description = "Supervisor administrative request to assign/reassign a dispute across investigators, domains, or statuses.")
    public record AssignDisputeRequest(
            @Schema(description = "Optional target investigator user UUID") UUID assigneeUserId,
            @Schema(description = "Target institutional domain (`INTERNAL_CAPITEC`, `PARTNER_BANKSERV`, `PARTNER_MERCHANT`)") UserDomain assignedDomain,
            @Schema(description = "Optional status override") DisputeStatus status,
            @Schema(description = "Supervisor assignment note") String note
    ) {}

    @Schema(description = "Customer identity summary with automatic POPIA PII masking when viewed by external `AFFILIATE` investigators.")
    public record CustomerSummaryDto(
            UUID id,
            @Schema(description = "Customer name (masked as `T. M****` for external affiliates)", example = "Thabo Mokoena") String fullName,
            @Schema(description = "Customer email (masked as `cu****@capitec.test` for external affiliates)") String email,
            @Schema(description = "13-digit SA ID number (masked as `850101****082` for external affiliates)", example = "8501015800082") String saIdNumber,
            @Schema(description = "Mobile number (masked as `+2782****192` for external affiliates)") String phoneNumber,
            @Schema(description = "Global One account number (masked as `******0341` for external affiliates)") String accountNumber,
            @Schema(description = "True when POPIA PII masking has been applied for an external partner domain") boolean piiMasked
    ) {}

    @Schema(description = "Chronological investigation note or customer communication entry.")
    public record NoteDto(
            UUID id,
            UUID disputeId,
            UUID authorId,
            String authorName,
            UserRole authorRole,
            String message,
            boolean internalOnly,
            boolean requiresCustomerReply,
            Instant createdAt
    ) {}

    @Schema(description = "Supporting evidence document metadata and cryptographic SHA-256 integrity digest.")
    public record AttachmentDto(
            UUID id,
            UUID disputeId,
            UUID uploadedBy,
            @Schema(description = "Sanitized original filename", example = "affidavit.pdf") String fileName,
            @Schema(description = "File size in bytes", example = "184320") long fileSizeBytes,
            @Schema(description = "Validated MIME type (`application/pdf`, `image/png`, `image/jpeg`)") String mimeType,
            @Schema(description = "64-character hex SHA-256 cryptographic checksum computed at upload time") String checksumSha256,
            Instant createdAt
    ) {}

    @Schema(description = "Prior case lineage snapshot attached when a dispute was re-opened from a previously REJECTED dispute.")
    public record LinkedDisputeLineageDto(
            UUID id,
            String disputeReference,
            DisputeStatus status,
            DisputeCategory category,
            BigDecimal amount,
            String reason,
            String resolutionSummary,
            Instant createdAt,
            Instant resolvedAt,
            List<NoteDto> previousNotes
    ) {}

    @Schema(description = "Summary representation of an active or historic disputed transaction.")
    public record DisputeSummaryDto(
            UUID id,
            @Schema(description = "Human-readable dispute case reference", example = "DSP-2026-10042") String disputeReference,
            UUID transactionId,
            String merchantName,
            String paymentRail,
            DisputeStatus status,
            @Schema(description = "True if the dispute is in an immutable terminal state (`RESOLVED`, `REFUNDED`, `REJECTED`)") boolean terminal,
            DisputeCategory category,
            BigDecimal amount,
            String reason,
            String resolutionSummary,
            UserDomain assignedDomain,
            UUID assignedUserId,
            String assignedUserName,
            UUID originalDisputeId,
            String originalDisputeReference,
            CustomerSummaryDto customer,
            long attachmentCount,
            Instant createdAt,
            Instant resolvedAt
    ) {}

    @Schema(description = "Complete dispute case aggregate including customer profile, notes, SHA-256 attachments, and linked case lineage.")
    public record DisputeDetailDto(
            UUID id,
            String disputeReference,
            UUID transactionId,
            String merchantName,
            String paymentRail,
            DisputeStatus status,
            boolean terminal,
            DisputeCategory category,
            BigDecimal amount,
            String reason,
            String resolutionSummary,
            UserDomain assignedDomain,
            UUID assignedUserId,
            String assignedUserName,
            UUID originalDisputeId,
            String originalDisputeReference,
            LinkedDisputeLineageDto originalDisputeLineage,
            List<String> childLinkedReferences,
            CustomerSummaryDto customer,
            List<NoteDto> notes,
            List<AttachmentDto> attachments,
            Instant createdAt,
            Instant resolvedAt
    ) {}

    @Schema(description = "Immutable security and lifecycle audit log entry persisted via independent `REQUIRES_NEW` transaction.")
    public record AuditLogDto(
            Long id,
            UUID actorId,
            String actorEmail,
            @Schema(description = "Severity classification (`INFO`, `WARN`, `HIGH`, `CRITICAL`)", example = "HIGH") String severity,
            @Schema(description = "Standardized event code (e.g. `UNAUTHORIZED_ACCESS_ATTEMPT`, `ACCOUNT_LOCKED_FRAUD_REPORTED`)", example = "UNAUTHORIZED_ACCESS_ATTEMPT") String eventType,
            String entityName,
            String entityId,
            String ipAddress,
            String metadata,
            Instant timestamp
    ) {}

    @Schema(description = "Aggregated investigator turnaround time and case productivity metrics calculated from `StaffWorkSession` records.")
    public record StaffProductivityRowDto(
            UUID staffId,
            String staffEmail,
            String staffFullName,
            long completedSessions,
            long activeSessions,
            long totalDurationSeconds,
            double averageDurationSeconds,
            long distinctDisputesHandled
    ) {}

    @Schema(description = "Individual investigator work-session timer log entry.")
    public record WorkSessionDetailDto(
            Long sessionId,
            UUID disputeId,
            String disputeReference,
            UUID staffId,
            String staffEmail,
            Instant startedAt,
            Instant completedAt,
            Long totalDurationSeconds
    ) {}

    @Schema(description = "Supervisor productivity report combining per-staff turnaround aggregates and recent session logs.")
    public record ProductivityReportDto(
            List<StaffProductivityRowDto> staffSummaries,
            List<WorkSessionDetailDto> recentSessions
    ) {}

    @Schema(description = "Asynchronous Apache Kafka event envelope emitted across dispute lifecycle, security audit, and alert topics.")
    public record KafkaEventEnvelopeDto(
            @Schema(description = "Unique event UUID") String eventId,
            @Schema(description = "Kafka topic name", example = "capitec.dispute.lifecycle.events") String topic,
            @Schema(description = "Domain event type", example = "DISPUTE_CREATED") String eventType,
            @Schema(description = "Kafka partition routing key (e.g. disputeReference or actorId)", example = "DSP-2026-10042") String messageKey,
            @Schema(description = "Serialized JSON event payload") String payloadJson,
            @Schema(description = "Transport mode (`KAFKA_BROKER` or `IN_MEMORY_BUS`)") String transportMode,
            @Schema(description = "UTC event emission timestamp") Instant publishedAt
    ) {}

    @Schema(description = "Real-time Apache Kafka event stream & Redis idempotency cache telemetry for Supervisor inspection.")
    public record KafkaStreamStatusDto(
            @Schema(description = "True when Apache Kafka broker integration is enabled") boolean kafkaBrokerEnabled,
            @Schema(description = "True when Redis distributed idempotency lock store is active") boolean redisCacheConnected,
            @Schema(description = "Configured TTL window in seconds for Redis dispute idempotency locks", example = "300") long idempotencyLockTtlSeconds,
            @Schema(description = "Configured Apache Kafka topics") List<String> configuredTopics,
            @Schema(description = "Total events published since startup") long totalEventsPublished,
            @Schema(description = "Total events consumed by @KafkaListener consumer group") long totalEventsConsumed,
            @Schema(description = "Most recent events published across all topics") List<KafkaEventEnvelopeDto> recentEvents
    ) {}
}
