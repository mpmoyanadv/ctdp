package za.co.capi.dispute.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import za.co.capi.dispute.domain.model.*;
import za.co.capi.dispute.domain.repository.*;
import za.co.capi.dispute.domain.repository.DisputeAttachmentRepository.DisputeAttachmentCountProjection;
import za.co.capi.dispute.infrastructure.cache.IdempotencyLockService;
import za.co.capi.dispute.infrastructure.messaging.KafkaEventService;
import za.co.capi.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capi.dispute.presentation.dto.ApiDtos.*;
import za.co.capi.dispute.presentation.exception.ApiException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);
    private static final BigDecimal MIN_DISPUTE_AMOUNT = new BigDecimal("0.01");
    private static final BigDecimal MAX_DISPUTE_AMOUNT = new BigDecimal("9999999.99");
    private static final Set<DisputeStatus> TERMINAL_STATUSES = EnumSet.of(
            DisputeStatus.RESOLVED,
            DisputeStatus.REFUNDED,
            DisputeStatus.REJECTED
    );

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
    public PageResponseDto<DisputeSummaryDto> listDisputesForActor(
            AppUserPrincipal actor,
            DisputeStatus statusFilter,
            int page,
            int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        Page<Dispute> disputesPage = switch (actor.getRole()) {
            case CUSTOMER -> statusFilter != null
                    ? disputeRepository.findByCustomerIdAndStatus(actor.getId(), statusFilter, pageable)
                    : disputeRepository.findByCustomerId(actor.getId(), pageable);
            case AFFILIATE -> statusFilter != null
                    ? disputeRepository.findByAssignedDomainAndStatus(actor.getUserDomain(), statusFilter, pageable)
                    : disputeRepository.findByAssignedDomain(actor.getUserDomain(), pageable);
            case STAFF, ADMIN -> statusFilter != null
                    ? disputeRepository.findByStatus(statusFilter, pageable)
                    : disputeRepository.findAll(pageable);
        };

        List<Dispute> pageContent = disputesPage.getContent();
        Set<UUID> disputeIds = new HashSet<>();
        Set<UUID> userIds = new HashSet<>();
        Set<UUID> originalIds = new HashSet<>();
        for (Dispute d : pageContent) {
            disputeIds.add(d.getId());
            if (d.getCustomerId() != null) {
                userIds.add(d.getCustomerId());
            }
            if (d.getAssignedUserId() != null) {
                userIds.add(d.getAssignedUserId());
            }
            if (d.getOriginalDisputeId() != null) {
                originalIds.add(d.getOriginalDisputeId());
            }
        }

        Map<UUID, AppUser> usersById = userIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(AppUser::getId, u -> u));
        Map<UUID, String> refById = originalIds.isEmpty()
                ? Map.of()
                : disputeRepository.findAllById(originalIds).stream()
                        .collect(Collectors.toMap(Dispute::getId, Dispute::getDisputeReference));
        Map<UUID, Long> attachmentCountById = disputeIds.isEmpty()
                ? Map.of()
                : attachmentRepository.countByDisputeIds(disputeIds).stream()
                        .collect(Collectors.toMap(
                                DisputeAttachmentCountProjection::getDisputeId,
                                DisputeAttachmentCountProjection::getAttachmentCount
                        ));

        Page<DisputeSummaryDto> dtoPage = disputesPage.map(
                d -> toSummaryDto(d, actor.getRole(), usersById, refById, attachmentCountById));
        return PageResponseDto.from(dtoPage);
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

        validateCreateDisputeRequest(request);

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
            BigDecimal amount = request.amount();

            if (request.transactionId() != null) {
                BankTransaction tx = transactionRepository.findById(request.transactionId())
                        .orElseThrow(() -> ApiException.notFound("Referenced bank transaction not found."));
                if (!tx.getCustomerId().equals(customerId)) {
                    log.warn("Dispute access denied: action=CREATE_DISPUTE_FOREIGN_TX targetId={} actorId={} role={}",
                            tx.getId(), customerId, viewerRole);
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
                if (tx.getStatus() == null || !tx.getStatus().isEligibleForDispute()) {
                    throw ApiException.conflict("409 Conflict: Only SETTLED transactions are eligible for dispute (current status: "
                            + (tx.getStatus() != null ? tx.getStatus().name() : "UNKNOWN") + ").");
                }
                if (disputeRepository.existsByTransactionIdAndStatusNotIn(tx.getId(), TERMINAL_STATUSES)) {
                    throw ApiException.conflict("409 Conflict: An active dispute already exists for transaction "
                            + tx.getTransactionReference() + ".");
                }
                // Derive authoritative values from the settled ledger transaction
                merchantName = tx.getMerchantName();
                paymentRail = tx.getPaymentRail();
                amount = tx.getAmount();
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
            dispute.setAmount(amount);
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

            log.info("Dispute created: disputeId={} customerId={} status={} channel={}",
                    saved.getId(), customerId, saved.getStatus(), saved.getSourceChannel());

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
            log.warn("Dispute access denied: action=UPLOAD_ATTACHMENT targetId={} actorId={} role={}",
                    disputeId, actor.getId(), actor.getRole());
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
            log.warn("Dispute access denied: action=CLAIM_DISPUTE targetId={} actorId={} role={}",
                    disputeId, actor.getId(), actor.getRole());
            throw ApiException.forbidden("Only internal Capi Staff or Admin can claim disputes.");
        }
        Dispute dispute = findDisputeAndVerifyBoundary(disputeId, actor, ipAddress, "CLAIM_DISPUTE");
        DisputeStatus previousStatus = dispute.getStatus();
        DisputeTransitionPolicy.validateTransition(previousStatus, DisputeStatus.IN_REVIEW, actor.getRole());

        dispute.setAssignedUserId(actor.getId());
        dispute.setAssignedDomain(UserDomain.INTERNAL_CAPI);
        dispute.setStatus(DisputeStatus.IN_REVIEW);
        dispute = disputeRepository.saveAndFlush(dispute);

        productivityService.startWorkSession(dispute.getId(), actor.getId());

        log.info("Dispute status transition: disputeId={} fromStatus={} toStatus={} actorId={} role={}",
                dispute.getId(), previousStatus, DisputeStatus.IN_REVIEW, actor.getId(), actor.getRole());

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
            DisputeStatus previousStatus = dispute.getStatus();
            DisputeTransitionPolicy.validateTransition(previousStatus, DisputeStatus.PENDING_CUSTOMER_ACTION, actor.getRole());
            dispute.setStatus(DisputeStatus.PENDING_CUSTOMER_ACTION);
            dispute = disputeRepository.saveAndFlush(dispute);
            log.info("Dispute status transition: disputeId={} fromStatus={} toStatus={} actorId={} role={}",
                    dispute.getId(), previousStatus, DisputeStatus.PENDING_CUSTOMER_ACTION, actor.getId(), actor.getRole());
        } else if (actor.getRole() == UserRole.CUSTOMER && dispute.getStatus() == DisputeStatus.PENDING_CUSTOMER_ACTION) {
            DisputeStatus previousStatus = dispute.getStatus();
            DisputeTransitionPolicy.validateTransition(previousStatus, DisputeStatus.IN_REVIEW, actor.getRole());
            dispute.setStatus(DisputeStatus.IN_REVIEW);
            dispute = disputeRepository.saveAndFlush(dispute);
            log.info("Dispute status transition: disputeId={} fromStatus={} toStatus={} actorId={} role={}",
                    dispute.getId(), previousStatus, DisputeStatus.IN_REVIEW, actor.getId(), actor.getRole());
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
        DisputeStatus previousStatus = dispute.getStatus();
        DisputeTransitionPolicy.validateTransition(previousStatus, DisputeStatus.ESCALATED, actor.getRole());

        productivityService.closeActiveSessionsForDispute(dispute.getId());

        dispute.setStatus(DisputeStatus.ESCALATED);
        dispute.setAssignedUserId(null);
        dispute.setAssignedDomain(UserDomain.INTERNAL_CAPI);
        dispute = disputeRepository.saveAndFlush(dispute);

        log.info("Dispute status transition: disputeId={} fromStatus={} toStatus={} actorId={} role={}",
                dispute.getId(), previousStatus, DisputeStatus.ESCALATED, actor.getId(), actor.getRole());

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
        DisputeStatus previousStatus = dispute.getStatus();
        DisputeTransitionPolicy.validateTransition(previousStatus, DisputeStatus.PENDING_EXTERNAL_REVIEW, actor.getRole());

        productivityService.closeActiveSessionsForDispute(dispute.getId());

        dispute.setAssignedDomain(request.partnerDomain());
        dispute.setAssignedUserId(null);
        dispute.setStatus(DisputeStatus.PENDING_EXTERNAL_REVIEW);
        dispute = disputeRepository.saveAndFlush(dispute);

        log.info("Dispute status transition: disputeId={} fromStatus={} toStatus={} actorId={} role={}",
                dispute.getId(), previousStatus, DisputeStatus.PENDING_EXTERNAL_REVIEW, actor.getId(), actor.getRole());

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
            log.warn("Dispute access denied: action=UPDATE_STATUS targetId={} actorId={} role={}",
                    disputeId, actor.getId(), actor.getRole());
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
        DisputeStatus previousStatus = dispute.getStatus();
        DisputeStatus newStatus = request.status();
        DisputeTransitionPolicy.validateTransition(previousStatus, newStatus, actor.getRole());

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

        dispute = disputeRepository.saveAndFlush(dispute);

        log.info("Dispute status transition: disputeId={} fromStatus={} toStatus={} actorId={} role={}",
                dispute.getId(), previousStatus, newStatus, actor.getId(), actor.getRole());

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

        DisputeStatus previousStatus = dispute.getStatus();
        DisputeStatus targetStatus = request.status();
        if (targetStatus == null) {
            if (targetDomain == UserDomain.PARTNER_BANKSERV || targetDomain == UserDomain.PARTNER_MERCHANT) {
                targetStatus = DisputeStatus.PENDING_EXTERNAL_REVIEW;
            } else if (request.assigneeUserId() != null) {
                targetStatus = DisputeStatus.IN_REVIEW;
            }
        }
        if (targetStatus != null && targetStatus != previousStatus) {
            DisputeTransitionPolicy.validateTransition(previousStatus, targetStatus, admin.getRole());
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

        dispute = disputeRepository.saveAndFlush(dispute);

        log.info("Dispute status transition: disputeId={} fromStatus={} toStatus={} actorId={} role={}",
                dispute.getId(), previousStatus, dispute.getStatus(), admin.getId(), admin.getRole());

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
        if (original.getTransactionId() != null
                && disputeRepository.existsByTransactionIdAndStatusNotIn(original.getTransactionId(), TERMINAL_STATUSES)) {
            throw ApiException.conflict("409 Conflict: An active dispute already exists for this transaction.");
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
                log.warn("Dispute access denied: action={} targetId={} actorId={} role={}",
                        attemptedAction, disputeId, actor.getId(), actor.getRole());
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
            log.warn("Dispute access denied: action={} targetId={} actorId={} role={}",
                    attemptedAction, dispute.getId(), actor.getId(), actor.getRole());
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
            log.warn("Dispute access denied: action={} targetId={} actorId={} role={}",
                    attemptedAction, dispute.getId(), actor.getId(), actor.getRole());
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
            Map<UUID, String> refById,
            Map<UUID, Long> attachmentCountById) {

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
                piiMaskingService.maskFreeText(d.getReason(), customer, viewerRole),
                piiMaskingService.maskFreeText(d.getResolutionSummary(), customer, viewerRole),
                d.getAssignedDomain(),
                d.getAssignedUserId(),
                assignee != null ? assignee.getFullName() : null,
                d.getOriginalDisputeId(),
                origRef,
                piiMaskingService.toCustomerSummary(customer, viewerRole),
                attachmentCountById.getOrDefault(d.getId(), 0L),
                d.getSourceChannel(),
                piiMaskingService.maskFreeText(d.getContactChannelMetadata(), customer, viewerRole),
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

        List<NoteDto> notes = rawNotes.stream().map(n -> toNoteDto(n, customer, viewerRole)).toList();
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
                        piiMaskingService.maskFreeText(orig.getReason(), customer, viewerRole),
                        piiMaskingService.maskFreeText(orig.getResolutionSummary(), customer, viewerRole),
                        orig.getCreatedAt(),
                        orig.getResolvedAt(),
                        origNotes.stream().map(n -> toNoteDto(n, customer, viewerRole)).toList()
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
                piiMaskingService.maskFreeText(d.getReason(), customer, viewerRole),
                piiMaskingService.maskFreeText(d.getResolutionSummary(), customer, viewerRole),
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
                piiMaskingService.maskFreeText(d.getContactChannelMetadata(), customer, viewerRole),
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

    private NoteDto toNoteDto(DisputeNote n, AppUser customer, UserRole viewerRole) {
        String authorName = (viewerRole == UserRole.AFFILIATE && n.getAuthorRole() == UserRole.CUSTOMER)
                ? piiMaskingService.maskName(n.getAuthorName())
                : n.getAuthorName();
        return new NoteDto(
                n.getId(),
                n.getDisputeId(),
                n.getAuthorId(),
                authorName,
                n.getAuthorRole(),
                piiMaskingService.maskFreeText(n.getMessage(), customer, viewerRole),
                n.isInternalOnly(),
                n.isRequiresCustomerReply(),
                n.getCreatedAt()
        );
    }

    private void validateCreateDisputeRequest(CreateDisputeRequest request) {
        if (request == null) {
            throw ApiException.badRequest("Dispute payload is required.");
        }
        if (request.category() == null) {
            throw ApiException.badRequest("Dispute category is required.");
        }
        if (request.amount() == null
                || request.amount().compareTo(MIN_DISPUTE_AMOUNT) < 0
                || request.amount().compareTo(MAX_DISPUTE_AMOUNT) > 0) {
            throw ApiException.badRequest("Amount must be between 0.01 and 9999999.99.");
        }
        if (Math.max(0, request.amount().stripTrailingZeros().scale()) > 2) {
            throw ApiException.badRequest("Amount must have at most 2 decimal places.");
        }
        String trimmedReason = request.reason() != null ? request.reason().trim() : "";
        if (trimmedReason.length() < 5 || trimmedReason.length() > 2000) {
            throw ApiException.badRequest("Reason must be between 5 and 2000 characters.");
        }
    }

    private String generateDisputeReference() {
        int year = ZonedDateTime.now(ZoneOffset.UTC).getYear();
        long seq = disputeRepository.getNextDisputeReferenceSequence();
        return String.format("DSP-%d-%05d", year, seq);
    }
}

