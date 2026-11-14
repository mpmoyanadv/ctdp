package za.co.capi.dispute.application.service;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import za.co.capi.dispute.domain.model.*;
import za.co.capi.dispute.domain.repository.*;
import za.co.capi.dispute.infrastructure.cache.IdempotencyLockService;
import za.co.capi.dispute.infrastructure.messaging.KafkaEventService;
import za.co.capi.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capi.dispute.presentation.dto.ApiDtos.*;
import za.co.capi.dispute.presentation.exception.ApiException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DisputeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DisputeRepository disputeRepository;
    private final DisputeNoteRepository noteRepository;
    private final DisputeAttachmentRepository attachmentRepository;
    private final BankTransactionRepository transactionRepository;
    private final AppUserRepository userRepository;
    private final AttachmentStorageService attachmentStorageService;
    private final PiiMaskingService piiMaskingService;
    private final ProductivityService productivityService;
    private final NotificationEmailService notificationEmailService;
    private final AuditService auditService;
    private final IdempotencyLockService idempotencyLockService;
    private final KafkaEventService kafkaEventService;

    public DisputeService(
            DisputeRepository disputeRepository,
            DisputeNoteRepository noteRepository,
            DisputeAttachmentRepository attachmentRepository,
            BankTransactionRepository transactionRepository,
            AppUserRepository userRepository,
            AttachmentStorageService attachmentStorageService,
            PiiMaskingService piiMaskingService,
            ProductivityService productivityService,
            NotificationEmailService notificationEmailService,
            AuditService auditService,
            IdempotencyLockService idempotencyLockService,
            KafkaEventService kafkaEventService) {
        this.disputeRepository = disputeRepository;
        this.noteRepository = noteRepository;
        this.attachmentRepository = attachmentRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.attachmentStorageService = attachmentStorageService;
        this.piiMaskingService = piiMaskingService;
        this.productivityService = productivityService;
        this.notificationEmailService = notificationEmailService;
        this.auditService = auditService;
        this.idempotencyLockService = idempotencyLockService;
        this.kafkaEventService = kafkaEventService;
    }

    @Transactional(readOnly = true)
    public List<DisputeSummaryDto> listDisputesForActor(AppUserPrincipal actor) {
        List<Dispute> disputes = switch (actor.getRole()) {
            case CUSTOMER -> disputeRepository.findByCustomerIdOrderByCreatedAtDesc(actor.getId());
            case AFFILIATE -> disputeRepository.findByAssignedDomainOrderByCreatedAtDesc(actor.getUserDomain());
            case STAFF, ADMIN -> disputeRepository.findAllByOrderByCreatedAtDesc();
        };

        Map<UUID, AppUser> usersById = userRepository.findAll().stream()
                .collect(Collectors.toMap(AppUser::getId, u -> u));
        Map<UUID, String> refById = disputeRepository.findAll().stream()
                .collect(Collectors.toMap(Dispute::getId, Dispute::getDisputeReference));

        return disputes.stream()
                .map(d -> toSummaryDto(d, actor.getRole(), usersById, refById))
                .toList();
    }

    @Transactional(readOnly = true)
    public DisputeDetailDto getDisputeDetail(UUID disputeId, AppUserPrincipal actor, String ipAddress) {
        Dispute dispute = findDisputeAndVerifyBoundary(disputeId, actor, ipAddress, "VIEW_DISPUTE");
        return buildDetailDto(dispute, actor.getRole());
    }

    @Transactional
    public DisputeDetailDto createDispute(
            CreateDisputeRequest request,
            List<MultipartFile> files,
            AppUserPrincipal actor,
            String ipAddress) {
        return createDispute(request, files, actor, ipAddress, null);
    }

    @Transactional
    public DisputeDetailDto createDispute(
            CreateDisputeRequest request,
            List<MultipartFile> files,
            AppUserPrincipal actor,
            String ipAddress,
            String idempotencyKey) {

        if (actor.getRole() != UserRole.CUSTOMER) {
            throw ApiException.forbidden("Only customers can file new transaction disputes.");
        }

        return createDisputeWithChannel(
                actor.getId(),
                actor.getEmail(),
                actor.getRole(),
                request,
                files,
                ipAddress,
                idempotencyKey,
                DisputeChannel.WEB_PORTAL,
                "Web Portal"
        );
    }

    @Transactional
    public DisputeDetailDto createDisputeWithChannel(
            UUID customerId,
            String actorEmail,
            UserRole viewerRole,
            CreateDisputeRequest request,
            List<MultipartFile> files,
            String ipAddress,
            String idempotencyKey,
            DisputeChannel sourceChannel,
            String contactChannelMetadata) {

        String lockKey = idempotencyLockService.buildDisputeLockKey(customerId, request, idempotencyKey);
        if (!idempotencyLockService.tryAcquireLock(lockKey, Instant.now().toString())) {
            auditService.logEvent(
                    customerId,
                    actorEmail,
                    "WARN",
                    "IDEMPOTENCY_DUPLICATE_PREVENTED",
                    "Dispute",
                    request.transactionId() != null ? request.transactionId().toString() : "IDEMPOTENCY_LOCK",
                    ipAddress,
                    Map.of(
                            "lockKey", lockKey,
                            "channel", sourceChannel != null ? sourceChannel.name() : DisputeChannel.WEB_PORTAL.name(),
                            "category", request.category().name(),
                            "amount", request.amount().toPlainString()
                    )
            );
            throw ApiException.conflict("409 Conflict: Duplicate dispute submission prevented by Redis idempotency lock.");
        }

        try {
            String merchantName = request.merchantName();
            String paymentRail = request.paymentRail();

            if (request.transactionId() != null) {
                BankTransaction tx = transactionRepository.findById(request.transactionId())
                        .orElseThrow(() -> ApiException.notFound("Referenced bank transaction not found."));
                if (!tx.getCustomerId().equals(customerId)) {
                    auditService.logEvent(
                            customerId,
                            actorEmail,
                            "HIGH",
                            "UNAUTHORIZED_ACCESS_ATTEMPT",
                            "BankTransaction",
                            tx.getId().toString(),
                            ipAddress,
                            Map.of("severity", "HIGH", "reason", "Attempted to dispute another customer's transaction")
                    );
                    throw ApiException.forbidden("403 Forbidden: Cannot dispute another customer's transaction.");
                }
                merchantName = tx.getMerchantName();
                paymentRail = tx.getPaymentRail();
            }

            if (merchantName == null || merchantName.isBlank()) {
                merchantName = "General Banking Transaction";
            }
            if (paymentRail == null || paymentRail.isBlank()) {
                paymentRail = "CARD_POS";
            }

            Dispute dispute = new Dispute();
            dispute.setDisputeReference(generateDisputeReference());
            dispute.setTransactionId(request.transactionId());
            dispute.setMerchantName(merchantName);
            dispute.setPaymentRail(paymentRail);
            dispute.setCustomerId(customerId);
            dispute.setAssignedDomain(UserDomain.INTERNAL_CAPI);
            dispute.setStatus(DisputeStatus.LOGGED);
            dispute.setCategory(request.category());
            dispute.setAmount(request.amount());
            dispute.setReason(request.reason().trim());
            dispute.setSourceChannel(sourceChannel != null ? sourceChannel : DisputeChannel.WEB_PORTAL);
            dispute.setContactChannelMetadata(contactChannelMetadata);
            dispute.setCreatedAt(Instant.now());

            Dispute saved = disputeRepository.save(dispute);

            if (files != null && !files.isEmpty()) {
                AppUser customerUser = userRepository.findById(customerId).orElse(null);
                AppUserPrincipal fileActor = customerUser != null
                        ? new AppUserPrincipal(customerUser)
                        : null;
                if (fileActor != null) {
                    attachmentStorageService.storeAttachments(saved, files, fileActor, ipAddress);
                }
            }

            auditService.logEvent(
                    customerId,
                    actorEmail,
                    "INFO",
                    "DISPUTE_CREATED",
                    "Dispute",
                    saved.getId().toString(),
                    ipAddress,
                    Map.of(
                            "disputeReference", saved.getDisputeReference(),
                            "channel", saved.getSourceChannel().name(),
                            "amount", saved.getAmount().toPlainString(),
                            "category", saved.getCategory().name(),
                            "idempotencyLockKey", lockKey
                    )
            );

            kafkaEventService.publishDisputeLifecycleEvent(
                    "DISPUTE_CREATED",
                    saved.getDisputeReference(),
                    Map.of(
                            "disputeId", saved.getId().toString(),
                            "disputeReference", saved.getDisputeReference(),
                            "customerId", customerId.toString(),
                            "channel", saved.getSourceChannel().name(),
                            "merchantName", saved.getMerchantName(),
                            "paymentRail", saved.getPaymentRail(),
                            "amount", saved.getAmount().toPlainString(),
                            "status", saved.getStatus().name()
                    )
            );

            return buildDetailDto(saved, viewerRole);
        } catch (RuntimeException ex) {
            idempotencyLockService.releaseLock(lockKey);
            throw ex;
        }
    }

    @Transactional
    public List<AttachmentDto> uploadAttachments(
            UUID disputeId,
            List<MultipartFile> files,
            AppUserPrincipal actor,
            String ipAddress) {

        if (actor.getRole() == UserRole.AFFILIATE) {
            auditService.logEvent(
                    actor.getId(),
                    actor.getEmail(),
                    "HIGH",
                    "UNAUTHORIZED_ACCESS_ATTEMPT",
                    "Dispute",
                    disputeId.toString(),
                    ipAddress,
                    Map.of("severity", "HIGH", "reason", "Affiliate attempted to upload attachments")
            );
            throw ApiException.forbidden("403 Forbidden: External affiliates cannot upload dispute evidence.");
        }
        Dispute dispute = findDisputeAndVerifyBoundary(disputeId, actor, ipAddress, "UPLOAD_ATTACHMENT");
        if (dispute.getStatus().isTerminal()) {
            throw ApiException.conflict("Dispute " + dispute.getDisputeReference() + " is in terminal state (" + dispute.getStatus() + ") and cannot accept file uploads.");
        }
        return attachmentStorageService.storeAttachments(dispute, files, actor, ipAddress);
    }

    @Transactional(readOnly = true)
    public DisputeAttachment getAuthorizedAttachment(UUID disputeId, UUID attachmentId, AppUserPrincipal actor, String ipAddress) {
        findDisputeAndVerifyBoundary(disputeId, actor, ipAddress, "DOWNLOAD_ATTACHMENT");
        return attachmentStorageService.getAttachmentForDispute(disputeId, attachmentId);
    }

    public Resource loadAttachmentFile(DisputeAttachment attachment) {
        return attachmentStorageService.loadResource(attachment);
    }

    @Transactional
    public DisputeDetailDto claimDispute(UUID disputeId, AppUserPrincipal actor, String ipAddress) {
        if (actor.getRole() != UserRole.STAFF && actor.getRole() != UserRole.ADMIN) {
            throw ApiException.forbidden("Only internal Capi Staff or Admin can claim disputes.");
        }
        Dispute dispute = findDisputeAndVerifyBoundary(disputeId, actor, ipAddress, "CLAIM_DISPUTE");
        DisputeTransitionPolicy.validateTransition(dispute.getStatus(), DisputeStatus.IN_REVIEW, actor.getRole());

        dispute.setAssignedUserId(actor.getId());
        dispute.setAssignedDomain(UserDomain.INTERNAL_CAPI);
        dispute.setStatus(DisputeStatus.IN_REVIEW);
        disputeRepository.save(dispute);

        productivityService.startWorkSession(dispute.getId(), actor.getId());

        auditService.logEvent(
                actor.getId(),
                actor.getEmail(),
                "INFO",
                "DISPUTE_CLAIMED",
                "Dispute",
                dispute.getId().toString(),
                ipAddress,
                Map.of("disputeReference", dispute.getDisputeReference(), "status", "IN_REVIEW")
        );

        kafkaEventService.publishDisputeLifecycleEvent(
                "DISPUTE_CLAIMED",
                dispute.getDisputeReference(),
                Map.of(
                        "disputeId", dispute.getId().toString(),
                        "disputeReference", dispute.getDisputeReference(),
                        "claimedBy", actor.getEmail(),
                        "status", dispute.getStatus().name()
                )
        );

        return buildDetailDto(dispute, actor.getRole());
    }

    @Transactional
    public DisputeDetailDto addNote(UUID disputeId, AddNoteRequest request, AppUserPrincipal actor, String ipAddress) {
        Dispute dispute = findDisputeAndVerifyBoundary(disputeId, actor, ipAddress, "ADD_NOTE");
        ensureNonTerminal(dispute);

        boolean internalOnly = (actor.getRole() == UserRole.STAFF || actor.getRole() == UserRole.ADMIN) && request.internalOnly();
        boolean requiresReply = (actor.getRole() == UserRole.STAFF || actor.getRole() == UserRole.ADMIN) && request.requiresCustomerReply() && !internalOnly;

        DisputeNote note = new DisputeNote();
        note.setDisputeId(dispute.getId());
        note.setAuthorId(actor.getId());
        note.setAuthorName(actor.getFullName());
        note.setAuthorRole(actor.getRole());
        note.setMessage(request.message().trim());
        note.setInternalOnly(internalOnly);
        note.setRequiresCustomerReply(requiresReply);
        note.setCreatedAt(Instant.now());
        noteRepository.save(note);

        if (requiresReply) {
            dispute.setStatus(DisputeStatus.PENDING_CUSTOMER_ACTION);
            disputeRepository.save(dispute);
        } else if (actor.getRole() == UserRole.CUSTOMER && dispute.getStatus() == DisputeStatus.PENDING_CUSTOMER_ACTION) {
            dispute.setStatus(DisputeStatus.IN_REVIEW);
            disputeRepository.save(dispute);
        }

        auditService.logEvent(
                actor.getId(),
                actor.getEmail(),
                "INFO",
                "DISPUTE_NOTE_ADDED",
                "Dispute",
                dispute.getId().toString(),
                ipAddress,
                Map.of(
                        "disputeReference", dispute.getDisputeReference(),
                        "requiresCustomerReply", requiresReply,
                        "internalOnly", internalOnly,
                        "resultingStatus", dispute.getStatus().name()
                )
        );

        return buildDetailDto(dispute, actor.getRole());
    }

    @Transactional
    public DisputeDetailDto escalateDispute(UUID disputeId, String reasonNote, AppUserPrincipal actor, String ipAddress) {
        if (actor.getRole() == UserRole.AFFILIATE) {
            throw ApiException.forbidden("External affiliates cannot escalate internal supervisor queues directly.");
        }
        Dispute dispute = findDisputeAndVerifyBoundary(disputeId, actor, ipAddress, "ESCALATE_DISPUTE");
        DisputeTransitionPolicy.validateTransition(dispute.getStatus(), DisputeStatus.ESCALATED, actor.getRole());

        productivityService.closeActiveSessionsForDispute(dispute.getId());

        dispute.setStatus(DisputeStatus.ESCALATED);
        dispute.setAssignedUserId(null);
        dispute.setAssignedDomain(UserDomain.INTERNAL_CAPI);
        disputeRepository.save(dispute);

        if (reasonNote != null && !reasonNote.isBlank()) {
            DisputeNote note = new DisputeNote();
            note.setDisputeId(dispute.getId());
            note.setAuthorId(actor.getId());
            note.setAuthorName(actor.getFullName());
            note.setAuthorRole(actor.getRole());
            note.setMessage("[ESCALATED] " + reasonNote.trim());
            note.setInternalOnly(false);
            note.setRequiresCustomerReply(false);
            noteRepository.save(note);
        }

        auditService.logEvent(
                actor.getId(),
                actor.getEmail(),
                "WARN",
                "DISPUTE_ESCALATED",
                "Dispute",
                dispute.getId().toString(),
                ipAddress,
                Map.of("disputeReference", dispute.getDisputeReference(), "escalatedByRole", actor.getRole().name())
        );

        notificationEmailService.sendEscalationNotification(
                "admin@capi.test",
                dispute.getDisputeReference(),
                actor.getEmail()
        );

        kafkaEventService.publishDisputeLifecycleEvent(
                "DISPUTE_ESCALATED",
                dispute.getDisputeReference(),
                Map.of(
                        "disputeId", dispute.getId().toString(),
                        "disputeReference", dispute.getDisputeReference(),
                        "escalatedBy", actor.getEmail(),
                        "status", dispute.getStatus().name()
                )
        );

        return buildDetailDto(dispute, actor.getRole());
    }

    @Transactional
    public DisputeDetailDto delegateToAffiliate(UUID disputeId, DelegateDisputeRequest request, AppUserPrincipal actor, String ipAddress) {
        if (actor.getRole() != UserRole.STAFF && actor.getRole() != UserRole.ADMIN) {
            throw ApiException.forbidden("Only internal Capi Staff or Admin can delegate disputes to external partners.");
        }
        if (request.partnerDomain() != UserDomain.PARTNER_BANKSERV && request.partnerDomain() != UserDomain.PARTNER_MERCHANT) {
            throw ApiException.badRequest("Target domain must be PARTNER_BANKSERV or PARTNER_MERCHANT.");
        }

        Dispute dispute = findDisputeAndVerifyBoundary(disputeId, actor, ipAddress, "DELEGATE_DISPUTE");
        DisputeTransitionPolicy.validateTransition(dispute.getStatus(), DisputeStatus.PENDING_EXTERNAL_REVIEW, actor.getRole());

        productivityService.closeActiveSessionsForDispute(dispute.getId());

        dispute.setAssignedDomain(request.partnerDomain());
        dispute.setAssignedUserId(null);
        dispute.setStatus(DisputeStatus.PENDING_EXTERNAL_REVIEW);
        disputeRepository.save(dispute);

        if (request.note() != null && !request.note().isBlank()) {
            DisputeNote note = new DisputeNote();
            note.setDisputeId(dispute.getId());
            note.setAuthorId(actor.getId());
            note.setAuthorName(actor.getFullName());
            note.setAuthorRole(actor.getRole());
            note.setMessage("[DELEGATED TO " + request.partnerDomain().name() + "] " + request.note().trim());
            note.setInternalOnly(false);
            note.setRequiresCustomerReply(false);
            noteRepository.save(note);
        }

        auditService.logEvent(
                actor.getId(),
                actor.getEmail(),
                "INFO",
                "DISPUTE_DELEGATED_EXTERNAL",
                "Dispute",
                dispute.getId().toString(),
                ipAddress,
                Map.of(
                        "disputeReference", dispute.getDisputeReference(),
                        "partnerDomain", request.partnerDomain().name()
                )
        );

        kafkaEventService.publishDisputeLifecycleEvent(
                "DISPUTE_DELEGATED_EXTERNAL",
                dispute.getDisputeReference(),
                Map.of(
                        "disputeId", dispute.getId().toString(),
                        "disputeReference", dispute.getDisputeReference(),
                        "partnerDomain", request.partnerDomain().name(),
                        "status", dispute.getStatus().name()
                )
        );

        return buildDetailDto(dispute, actor.getRole());
    }

    @Transactional
    public DisputeDetailDto updateStatus(UUID disputeId, StatusUpdateRequest request, AppUserPrincipal actor, String ipAddress) {
        if (actor.getRole() != UserRole.STAFF && actor.getRole() != UserRole.ADMIN) {
            auditService.logEvent(
                    actor.getId(),
                    actor.getEmail(),
                    "HIGH",
                    "UNAUTHORIZED_ACCESS_ATTEMPT",
                    "Dispute",
                    disputeId.toString(),
                    ipAddress,
                    Map.of(
                            "severity", "HIGH",
                            "actorRole", actor.getRole().name(),
                            "reason", "Unauthorized role attempted privileged status update"
                    )
            );
            throw ApiException.forbidden("403 Forbidden: Only internal Capi Staff or Admin may update investigation status.");
        }

        Dispute dispute = findDisputeAndVerifyBoundary(disputeId, actor, ipAddress, "UPDATE_STATUS");
        DisputeStatus newStatus = request.status();
        DisputeTransitionPolicy.validateTransition(dispute.getStatus(), newStatus, actor.getRole());

        dispute.setStatus(newStatus);
        if (newStatus == DisputeStatus.IN_REVIEW && dispute.getAssignedDomain() != UserDomain.INTERNAL_CAPI) {
            dispute.setAssignedDomain(UserDomain.INTERNAL_CAPI);
        }
        if (request.resolutionSummary() != null && !request.resolutionSummary().isBlank()) {
            dispute.setResolutionSummary(request.resolutionSummary().trim());
        }

        if (newStatus.isTerminal()) {
            dispute.setResolvedAt(Instant.now());
            productivityService.closeActiveSessionsForDispute(dispute.getId());
        } else if (newStatus == DisputeStatus.IN_REVIEW && (actor.getRole() == UserRole.STAFF || actor.getRole() == UserRole.ADMIN)) {
            dispute.setAssignedUserId(actor.getId());
            productivityService.startWorkSession(dispute.getId(), actor.getId());
        }

        disputeRepository.save(dispute);

        if (request.resolutionSummary() != null && !request.resolutionSummary().isBlank()) {
            DisputeNote note = new DisputeNote();
            note.setDisputeId(dispute.getId());
            note.setAuthorId(actor.getId());
            note.setAuthorName(actor.getFullName());
            note.setAuthorRole(actor.getRole());
            note.setMessage("[" + newStatus.name() + "] " + request.resolutionSummary().trim());
            note.setInternalOnly(false);
            note.setRequiresCustomerReply(false);
            noteRepository.save(note);
        }

        auditService.logEvent(
                actor.getId(),
                actor.getEmail(),
                "INFO",
                "DISPUTE_STATUS_CHANGED",
                "Dispute",
                dispute.getId().toString(),
                ipAddress,
                Map.of(
                        "disputeReference", dispute.getDisputeReference(),
                        "newStatus", newStatus.name(),
                        "terminal", newStatus.isTerminal()
                )
        );

        kafkaEventService.publishDisputeLifecycleEvent(
                "DISPUTE_STATUS_CHANGED",
                dispute.getDisputeReference(),
                Map.of(
                        "disputeId", dispute.getId().toString(),
                        "disputeReference", dispute.getDisputeReference(),
                        "newStatus", newStatus.name(),
                        "terminal", newStatus.isTerminal()
                )
        );

        return buildDetailDto(dispute, actor.getRole());
    }

    @Transactional
    public DisputeDetailDto adminAssignDispute(UUID disputeId, AssignDisputeRequest request, AppUserPrincipal admin, String ipAddress) {
        if (admin.getRole() != UserRole.ADMIN) {
            throw ApiException.forbidden("403 Forbidden: Only ADMIN supervisors can assign disputes.");
        }
        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> ApiException.notFound("Dispute not found."));
        ensureNonTerminal(dispute);

        UserDomain targetDomain = request.assignedDomain() != null
                ? request.assignedDomain()
                : dispute.getAssignedDomain();
        if (targetDomain == UserDomain.CUSTOMER) {
            throw ApiException.badRequest("Disputes cannot be assigned to the CUSTOMER domain.");
        }

        if (request.assigneeUserId() != null) {
            AppUser assignee = userRepository.findById(request.assigneeUserId())
                    .orElseThrow(() -> ApiException.notFound("Assignee user not found."));
            if (assignee.isLocked()) {
                throw ApiException.badRequest("Cannot assign dispute to a locked user account.");
            }
            validateAssigneeCompatibility(assignee, targetDomain);
        }

        DisputeStatus targetStatus = request.status();
        if (targetStatus == null) {
            if (targetDomain == UserDomain.PARTNER_BANKSERV || targetDomain == UserDomain.PARTNER_MERCHANT) {
                targetStatus = DisputeStatus.PENDING_EXTERNAL_REVIEW;
            } else if (request.assigneeUserId() != null) {
                targetStatus = DisputeStatus.IN_REVIEW;
            }
        }
        if (targetStatus != null && targetStatus != dispute.getStatus()) {
            DisputeTransitionPolicy.validateTransition(dispute.getStatus(), targetStatus, admin.getRole());
        }

        productivityService.closeActiveSessionsForDispute(dispute.getId());

        dispute.setAssignedDomain(targetDomain);
        dispute.setAssignedUserId(request.assigneeUserId());

        if (targetStatus != null) {
            dispute.setStatus(targetStatus);
            if (targetStatus.isTerminal()) {
                dispute.setResolvedAt(Instant.now());
            } else if (targetStatus == DisputeStatus.IN_REVIEW && request.assigneeUserId() != null) {
                productivityService.startWorkSession(dispute.getId(), request.assigneeUserId());
            }
        }

        disputeRepository.save(dispute);

        auditService.logEvent(
                admin.getId(),
                admin.getEmail(),
                "INFO",
                "ADMIN_DISPUTE_ASSIGNED",
                "Dispute",
                dispute.getId().toString(),
                ipAddress,
                Map.of(
                        "disputeReference", dispute.getDisputeReference(),
                        "assignedDomain", dispute.getAssignedDomain().name(),
                        "assignedUserId", dispute.getAssignedUserId() != null ? dispute.getAssignedUserId().toString() : "UNASSIGNED",
                        "status", dispute.getStatus().name()
                )
        );

        return buildDetailDto(dispute, admin.getRole());
    }

    private void validateAssigneeCompatibility(AppUser assignee, UserDomain targetDomain) {
        if (targetDomain == UserDomain.INTERNAL_CAPI) {
            if ((assignee.getRole() != UserRole.STAFF && assignee.getRole() != UserRole.ADMIN)
                    || assignee.getUserDomain() != UserDomain.INTERNAL_CAPI) {
                throw ApiException.badRequest("Internal assignments require an active STAFF or ADMIN user in INTERNAL_CAPI");
            }
            return;
        }
        if (targetDomain == UserDomain.PARTNER_BANKSERV || targetDomain == UserDomain.PARTNER_MERCHANT) {
            if (assignee.getRole() != UserRole.AFFILIATE || assignee.getUserDomain() != targetDomain) {
                throw ApiException.badRequest("Partner assignments require an active AFFILIATE user in " + targetDomain.name());
            }
            return;
        }
        throw ApiException.badRequest("Unsupported target domain for dispute assignment: " + targetDomain.name());
    }

    @Transactional
    public DisputeDetailDto reopenRejectedDispute(
            UUID rejectedDisputeId,
            ReopenDisputeRequest request,
            AppUserPrincipal actor,
            String ipAddress) {

        Dispute original = findDisputeAndVerifyBoundary(rejectedDisputeId, actor, ipAddress, "REOPEN_REJECTED_DISPUTE");

        if (actor.getRole() != UserRole.CUSTOMER) {
            throw ApiException.forbidden("Only the customer can re-open a rejected dispute as a linked case.");
        }
        if (original.getStatus() != DisputeStatus.REJECTED) {
            throw ApiException.badRequest("Only disputes in REJECTED status can be re-opened as a linked dispute.");
        }

        Dispute linked = new Dispute();
        linked.setDisputeReference(generateDisputeReference());
        linked.setTransactionId(original.getTransactionId());
        linked.setMerchantName(original.getMerchantName());
        linked.setPaymentRail(original.getPaymentRail());
        linked.setCustomerId(actor.getId());
        linked.setAssignedDomain(UserDomain.INTERNAL_CAPI);
        linked.setStatus(DisputeStatus.LOGGED);
        linked.setCategory(request.category() != null ? request.category() : original.getCategory());
        linked.setAmount(original.getAmount());
        linked.setReason(request.reason().trim());
        linked.setOriginalDisputeId(original.getId());
        linked.setCreatedAt(Instant.now());

        Dispute savedLinked = disputeRepository.save(linked);

        auditService.logEvent(
                actor.getId(),
                actor.getEmail(),
                "INFO",
                "DISPUTE_REOPENED_LINKED",
                "Dispute",
                savedLinked.getId().toString(),
                ipAddress,
                Map.of(
                        "newDisputeReference", savedLinked.getDisputeReference(),
                        "originalDisputeId", original.getId().toString(),
                        "originalDisputeReference", original.getDisputeReference()
                )
        );

        kafkaEventService.publishDisputeLifecycleEvent(
                "DISPUTE_REOPENED_LINKED",
                savedLinked.getDisputeReference(),
                Map.of(
                        "newDisputeId", savedLinked.getId().toString(),
                        "newDisputeReference", savedLinked.getDisputeReference(),
                        "originalDisputeId", original.getId().toString(),
                        "originalDisputeReference", original.getDisputeReference()
                )
        );

        return buildDetailDto(savedLinked, actor.getRole());
    }

    private Dispute findDisputeAndVerifyBoundary(UUID disputeId, AppUserPrincipal actor, String ipAddress, String attemptedAction) {
        Optional<Dispute> disputeOpt = disputeRepository.findById(disputeId);
        if (disputeOpt.isEmpty()) {
            if (actor.getRole() == UserRole.CUSTOMER || actor.getRole() == UserRole.AFFILIATE) {
                auditService.logEvent(
                        actor.getId(),
                        actor.getEmail(),
                        "HIGH",
                        "UNAUTHORIZED_ACCESS_ATTEMPT",
                        "Dispute",
                        disputeId.toString(),
                        ipAddress,
                        Map.of(
                                "severity", "HIGH",
                                "actorId", actor.getId().toString(),
                                "requestedId", disputeId.toString(),
                                "action", attemptedAction,
                                "reason", "Probed unknown or unauthorized dispute identifier"
                        )
                );
                throw ApiException.forbidden("403 Forbidden: Access Denied for requested dispute resource.");
            }
            throw ApiException.notFound("Dispute not found: " + disputeId);
        }

        Dispute dispute = disputeOpt.get();

        if (actor.getRole() == UserRole.CUSTOMER && !dispute.getCustomerId().equals(actor.getId())) {
            auditService.logEvent(
                    actor.getId(),
                    actor.getEmail(),
                    "HIGH",
                    "UNAUTHORIZED_ACCESS_ATTEMPT",
                    "Dispute",
                    dispute.getId().toString(),
                    ipAddress,
                    Map.of(
                            "severity", "HIGH",
                            "actorId", actor.getId().toString(),
                            "actorEmail", actor.getEmail(),
                            "requestedId", dispute.getId().toString(),
                            "disputeReference", dispute.getDisputeReference(),
                            "ownerCustomerId", dispute.getCustomerId().toString(),
                            "action", attemptedAction
                    )
            );
            throw ApiException.forbidden("403 Forbidden: Access Denied. You are not authorized to view or modify another customer's dispute.");
        }

        if (actor.getRole() == UserRole.AFFILIATE && dispute.getAssignedDomain() != actor.getUserDomain()) {
            auditService.logEvent(
                    actor.getId(),
                    actor.getEmail(),
                    "HIGH",
                    "UNAUTHORIZED_ACCESS_ATTEMPT",
                    "Dispute",
                    dispute.getId().toString(),
                    ipAddress,
                    Map.of(
                            "severity", "HIGH",
                            "actorId", actor.getId().toString(),
                            "actorDomain", actor.getUserDomain().name(),
                            "requestedId", dispute.getId().toString(),
                            "assignedDomain", dispute.getAssignedDomain().name(),
                            "action", attemptedAction
                    )
            );
            throw ApiException.forbidden("403 Forbidden: Dispute is not delegated to your external partner domain.");
        }

        return dispute;
    }

    private void ensureNonTerminal(Dispute dispute) {
        if (dispute.getStatus().isTerminal()) {
            throw ApiException.conflict("Dispute " + dispute.getDisputeReference() + " is in terminal status (" + dispute.getStatus() + ") and cannot be modified.");
        }
    }

    private DisputeSummaryDto toSummaryDto(
            Dispute d,
            UserRole viewerRole,
            Map<UUID, AppUser> usersById,
            Map<UUID, String> refById) {

        AppUser customer = usersById.get(d.getCustomerId());
        AppUser assignee = d.getAssignedUserId() != null ? usersById.get(d.getAssignedUserId()) : null;
        String origRef = d.getOriginalDisputeId() != null ? refById.get(d.getOriginalDisputeId()) : null;

        return new DisputeSummaryDto(
                d.getId(),
                d.getDisputeReference(),
                d.getTransactionId(),
                d.getMerchantName(),
                d.getPaymentRail(),
                d.getStatus(),
                d.getStatus().isTerminal(),
                d.getCategory(),
                d.getAmount(),
                d.getReason(),
                d.getResolutionSummary(),
                d.getAssignedDomain(),
                d.getAssignedUserId(),
                assignee != null ? assignee.getFullName() : null,
                d.getOriginalDisputeId(),
                origRef,
                piiMaskingService.toCustomerSummary(customer, viewerRole),
                attachmentRepository.countByDisputeId(d.getId()),
                d.getSourceChannel(),
                d.getContactChannelMetadata(),
                d.getCreatedAt(),
                d.getResolvedAt()
        );
    }

    private DisputeDetailDto buildDetailDto(Dispute d, UserRole viewerRole) {
        AppUser customer = userRepository.findById(d.getCustomerId()).orElse(null);
        AppUser assignee = d.getAssignedUserId() != null ? userRepository.findById(d.getAssignedUserId()).orElse(null) : null;

        boolean canSeeInternal = viewerRole == UserRole.STAFF || viewerRole == UserRole.ADMIN;
        List<DisputeNote> rawNotes = canSeeInternal
                ? noteRepository.findByDisputeIdOrderByCreatedAtAsc(d.getId())
                : noteRepository.findByDisputeIdAndInternalOnlyFalseOrderByCreatedAtAsc(d.getId());

        List<NoteDto> notes = rawNotes.stream().map(this::toNoteDto).toList();
        List<AttachmentDto> attachments = attachmentRepository.findByDisputeIdOrderByCreatedAtAsc(d.getId())
                .stream()
                .map(attachmentStorageService::toDto)
                .toList();

        String originalRef = null;
        LinkedDisputeLineageDto lineageDto = null;
        if (d.getOriginalDisputeId() != null) {
            Optional<Dispute> origOpt = disputeRepository.findById(d.getOriginalDisputeId());
            if (origOpt.isPresent()) {
                Dispute orig = origOpt.get();
                originalRef = orig.getDisputeReference();
                List<DisputeNote> origNotes = canSeeInternal
                        ? noteRepository.findByDisputeIdOrderByCreatedAtAsc(orig.getId())
                        : noteRepository.findByDisputeIdAndInternalOnlyFalseOrderByCreatedAtAsc(orig.getId());
                lineageDto = new LinkedDisputeLineageDto(
                        orig.getId(),
                        orig.getDisputeReference(),
                        orig.getStatus(),
                        orig.getCategory(),
                        orig.getAmount(),
                        orig.getReason(),
                        orig.getResolutionSummary(),
                        orig.getCreatedAt(),
                        orig.getResolvedAt(),
                        origNotes.stream().map(this::toNoteDto).toList()
                );
            }
        }

        List<String> childRefs = disputeRepository.findByOriginalDisputeIdOrderByCreatedAtDesc(d.getId())
                .stream()
                .map(Dispute::getDisputeReference)
                .toList();

        return new DisputeDetailDto(
                d.getId(),
                d.getDisputeReference(),
                d.getTransactionId(),
                d.getMerchantName(),
                d.getPaymentRail(),
                d.getStatus(),
                d.getStatus().isTerminal(),
                d.getCategory(),
                d.getAmount(),
                d.getReason(),
                d.getResolutionSummary(),
                d.getAssignedDomain(),
                d.getAssignedUserId(),
                assignee != null ? assignee.getFullName() : null,
                d.getOriginalDisputeId(),
                originalRef,
                lineageDto,
                childRefs,
                piiMaskingService.toCustomerSummary(customer, viewerRole),
                notes,
                attachments,
                d.getSourceChannel(),
                d.getContactChannelMetadata(),
                d.getCreatedAt(),
                d.getResolvedAt()
        );
    }

    @Transactional(readOnly = true)
    public DisputeDetailDto refreshDetailDto(UUID disputeId, UserRole viewerRole) {
        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> ApiException.notFound("Dispute not found: " + disputeId));
        return buildDetailDto(dispute, viewerRole);
    }

    private NoteDto toNoteDto(DisputeNote n) {
        return new NoteDto(
                n.getId(),
                n.getDisputeId(),
                n.getAuthorId(),
                n.getAuthorName(),
                n.getAuthorRole(),
                n.getMessage(),
                n.isInternalOnly(),
                n.isRequiresCustomerReply(),
                n.getCreatedAt()
        );
    }

    private String generateDisputeReference() {
        int year = ZonedDateTime.now(ZoneOffset.UTC).getYear();
        int num = 10000 + RANDOM.nextInt(90000);
        return "DSP-" + year + "-" + num;
    }
}

