package za.co.capi.dispute.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.capi.dispute.domain.model.*;
import za.co.capi.dispute.domain.repository.AppUserRepository;
import za.co.capi.dispute.domain.repository.BankTransactionRepository;
import za.co.capi.dispute.domain.repository.DisputeNoteRepository;
import za.co.capi.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capi.dispute.presentation.dto.ApiDtos.*;
import za.co.capi.dispute.presentation.exception.ApiException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OmnichannelDisputeService {

    private static final Pattern TXN_REF_PATTERN = Pattern.compile("(TXN-\\d{4}-\\d{3,6})", Pattern.CASE_INSENSITIVE);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository userRepository;
    private final BankTransactionRepository transactionRepository;
    private final DisputeNoteRepository noteRepository;
    private final DisputeService disputeService;
    private final NotificationEmailService notificationEmailService;
    private final AuditService auditService;

    public OmnichannelDisputeService(
            AppUserRepository userRepository,
            BankTransactionRepository transactionRepository,
            DisputeNoteRepository noteRepository,
            DisputeService disputeService,
            NotificationEmailService notificationEmailService,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.noteRepository = noteRepository;
        this.disputeService = disputeService;
        this.notificationEmailService = notificationEmailService;
        this.auditService = auditService;
    }

    @Transactional
    public OmnichannelSimulationResponse simulateSmsOrWhatsapp(
            SmsWebhookSimulationRequest request,
            AppUserPrincipal actor,
            String ipAddress) {

        DisputeChannel channel = request.channel() != null ? request.channel() : DisputeChannel.SMS;
        if (channel != DisputeChannel.SMS && channel != DisputeChannel.WHATSAPP) {
            throw ApiException.badRequest("Channel for messaging webhook simulation must be SMS or WHATSAPP.");
        }

        AppUser customer = resolveTargetCustomer(request.fromPhoneNumber(), actor, ipAddress);
        BankTransaction tx = resolveTransaction(customer.getId(), request.transactionId(), request.messageBody(), actor, ipAddress);

        DisputeCategory inferredCategory = inferCategoryFromMessage(request.messageBody(), tx);
        String merchant = tx != null ? tx.getMerchantName() : "Mobile Channel Reported Transaction";
        String rail = tx != null ? tx.getPaymentRail() : "MOBILE_ALERT";
        BigDecimal amount = tx != null ? tx.getAmount() : new BigDecimal("500.00");

        String callerPhone = customer.getPhoneNumber() != null ? customer.getPhoneNumber() : "+27000000000";
        String metadata = channel.name() + " Gateway | MSISDN: " + callerPhone
                + (tx != null ? " | Matched: " + tx.getTransactionReference() : "");

        CreateDisputeRequest createReq = new CreateDisputeRequest(
                tx != null ? tx.getId() : null,
                merchant,
                rail,
                inferredCategory,
                amount,
                "[" + channel.name() + " Intake] " + request.messageBody().trim()
        );

        DisputeDetailDto created = disputeService.createDisputeWithChannel(
                customer.getId(),
                actor.getEmail(),
                actor.getRole(),
                createReq,
                List.of(),
                ipAddress,
                request.idempotencyKey(),
                channel,
                metadata
        );

        String transcript = "[" + channel.name() + " INBOUND from " + callerPhone + "]: \"" + request.messageBody().trim() + "\"";
        saveChannelNote(created.id(), customer, transcript);

        String outboundReply = String.format(
                "Capi Bank %s Auto-Reply: Dispute %s has been logged for %s (R%s). We have flagged this for urgent review. Reply STATUS or open your portal to attach documents.",
                channel.name(),
                created.disputeReference(),
                merchant,
                amount.toPlainString()
        );

        notificationEmailService.sendOmnichannelDisputeConfirmation(
                customer.getEmail(),
                customer.getFullName(),
                channel,
                created.disputeReference(),
                merchant,
                amount.toPlainString(),
                outboundReply
        );

        DisputeDetailDto refreshed = disputeService.refreshDetailDto(created.id(), actor.getRole());
        return new OmnichannelSimulationResponse(refreshed, channel, outboundReply, transcript, Instant.now());
    }

    @Transactional
    public OmnichannelSimulationResponse simulateVoiceCall(
            VoiceCallSimulationRequest request,
            AppUserPrincipal actor,
            String ipAddress) {

        AppUser customer = resolveTargetCustomer(request.callerPhoneNumber(), actor, ipAddress);
        BankTransaction tx = resolveTransaction(
                customer.getId(),
                request.transactionId(),
                request.transactionReference() != null ? request.transactionReference() : request.callerStatement(),
                actor,
                ipAddress
        );

        DisputeCategory category = request.category() != null
                ? request.category()
                : inferCategoryFromMessage(request.callerStatement(), tx);

        String merchant = tx != null ? tx.getMerchantName() : "Call Centre Reported Merchant";
        String rail = tx != null ? tx.getPaymentRail() : "CARD_POS";
        BigDecimal amount = tx != null ? tx.getAmount() : new BigDecimal("500.00");

        String callId = "CALL-" + (100000 + RANDOM.nextInt(900000));
        String callerPhone = customer.getPhoneNumber() != null ? customer.getPhoneNumber() : "+27000000000";
        String scenarioTag = request.scenario() != null && !request.scenario().isBlank()
                ? request.scenario().trim()
                : "CUSTOMER_VOICE_REPORT";

        String metadata = callId + " | Caller: " + callerPhone + " | Scenario: " + scenarioTag
                + (request.cardFreezeRequested() ? " | Precautionary Card Hold" : "");

        CreateDisputeRequest createReq = new CreateDisputeRequest(
                tx != null ? tx.getId() : null,
                merchant,
                rail,
                category,
                amount,
                "[PHONE CALL - " + scenarioTag + "] " + request.callerStatement().trim()
        );

        DisputeDetailDto created = disputeService.createDisputeWithChannel(
                customer.getId(),
                actor.getEmail(),
                actor.getRole(),
                createReq,
                List.of(),
                ipAddress,
                request.idempotencyKey(),
                DisputeChannel.PHONE_CALL,
                metadata
        );

        StringBuilder transcript = new StringBuilder();
        transcript.append("[VOICE CALL RECORDING ").append(callId).append(" | IVR Caller Verified: ").append(callerPhone).append("]\n")
                .append("Scenario: ").append(scenarioTag).append("\n")
                .append("Customer Statement: ").append(request.callerStatement().trim());
        if (request.agentNotes() != null && !request.agentNotes().isBlank()) {
            transcript.append("\nAgent Notes: ").append(request.agentNotes().trim());
        }
        if (request.cardFreezeRequested()) {
            transcript.append("\nAction Taken: Temporary precautionary card freeze flag requested during call.");
        }

        saveChannelNote(created.id(), customer, transcript.toString());

        String outboundReply = String.format(
                "Voice Call %s completed. Dispute %s logged for %s (R%s)%s. Confirmation SMS/email sent to customer.",
                callId,
                created.disputeReference(),
                merchant,
                amount.toPlainString(),
                request.cardFreezeRequested() ? " with precautionary card hold" : ""
        );

        notificationEmailService.sendOmnichannelDisputeConfirmation(
                customer.getEmail(),
                customer.getFullName(),
                DisputeChannel.PHONE_CALL,
                created.disputeReference(),
                merchant,
                amount.toPlainString(),
                transcript.toString().replace("\n", "<br/>")
        );

        DisputeDetailDto refreshed = disputeService.refreshDetailDto(created.id(), actor.getRole());
        return new OmnichannelSimulationResponse(refreshed, DisputeChannel.PHONE_CALL, outboundReply, transcript.toString(), Instant.now());
    }

    private AppUser resolveTargetCustomer(String phoneNumber, AppUserPrincipal actor, String ipAddress) {
        if (actor.getRole() == UserRole.CUSTOMER) {
            AppUser currentCustomer = userRepository.findById(actor.getId())
                    .orElseThrow(() -> ApiException.notFound("Authenticated customer profile not found."));
            if (phoneNumber != null && !phoneNumber.isBlank()
                    && currentCustomer.getPhoneNumber() != null
                    && !phoneNumber.trim().equalsIgnoreCase(currentCustomer.getPhoneNumber().trim())) {
                auditService.logEvent(
                        actor.getId(),
                        actor.getEmail(),
                        "HIGH",
                        "UNAUTHORIZED_ACCESS_ATTEMPT",
                        "AppUser",
                        phoneNumber.trim(),
                        ipAddress,
                        Map.of("reason", "Customer attempted omnichannel simulation using another MSISDN")
                );
                throw ApiException.forbidden("403 Forbidden: Cannot simulate inbound SMS/call for another customer's phone number.");
            }
            return currentCustomer;
        }

        if (actor.getRole() != UserRole.STAFF && actor.getRole() != UserRole.ADMIN) {
            throw ApiException.forbidden("External affiliates cannot initiate omnichannel dispute intake.");
        }

        if (phoneNumber != null && !phoneNumber.isBlank()) {
            return userRepository.findFirstByPhoneNumber(phoneNumber.trim())
                    .orElseThrow(() -> ApiException.notFound("No retail customer registered with phone number: " + phoneNumber));
        }

        return userRepository.findByEmailIgnoreCase("customer@capi.test")
                .orElseThrow(() -> ApiException.notFound("Default demo customer not found."));
    }

    private BankTransaction resolveTransaction(
            UUID customerId,
            UUID transactionId,
            String textWithOptionalRef,
            AppUserPrincipal actor,
            String ipAddress) {

        if (transactionId != null) {
            BankTransaction tx = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> ApiException.notFound("Transaction not found: " + transactionId));
            verifyTransactionOwnership(tx, customerId, actor, ipAddress);
            return tx;
        }

        if (textWithOptionalRef != null) {
            Matcher matcher = TXN_REF_PATTERN.matcher(textWithOptionalRef);
            if (matcher.find()) {
                String ref = matcher.group(1).toUpperCase(Locale.ROOT);
                BankTransaction tx = transactionRepository.findByTransactionReferenceIgnoreCase(ref)
                        .orElseThrow(() -> ApiException.notFound("Referenced transaction not found: " + ref));
                verifyTransactionOwnership(tx, customerId, actor, ipAddress);
                return tx;
            }
        }

        List<BankTransaction> customerTxs = transactionRepository.findByCustomerIdOrderByTransactedAtDesc(customerId);
        return customerTxs.isEmpty() ? null : customerTxs.getFirst();
    }

    private void verifyTransactionOwnership(BankTransaction tx, UUID customerId, AppUserPrincipal actor, String ipAddress) {
        if (!tx.getCustomerId().equals(customerId)) {
            auditService.logEvent(
                    actor.getId(),
                    actor.getEmail(),
                    "HIGH",
                    "UNAUTHORIZED_ACCESS_ATTEMPT",
                    "BankTransaction",
                    tx.getId().toString(),
                    ipAddress,
                    Map.of("reason", "Omnichannel simulation referenced another customer's transaction")
            );
            throw ApiException.forbidden("403 Forbidden: Referenced transaction does not belong to target customer.");
        }
    }

    private DisputeCategory inferCategoryFromMessage(String text, BankTransaction tx) {
        String lower = text != null ? text.toLowerCase(Locale.ROOT) : "";
        if (lower.contains("atm") || lower.contains("dispense") || lower.contains("cash")
                || (tx != null && "ATM_WITHDRAWAL".equalsIgnoreCase(tx.getPaymentRail()))) {
            return DisputeCategory.ATM_DISPENSE_ERROR;
        }
        if (lower.contains("twice") || lower.contains("duplicate") || lower.contains("double")) {
            return DisputeCategory.DUPLICATE_CHARGE;
        }
        if (lower.contains("payshap") || (tx != null && "PAYSHAP".equalsIgnoreCase(tx.getPaymentRail()))) {
            return DisputeCategory.PAYSHAP_WRONG_RECIPIENT;
        }
        if (lower.contains("shell") || lower.contains("fuel") || lower.contains("filling") || lower.contains("petrol") || lower.contains("skim")) {
            return DisputeCategory.FRAUDULENT_CARD_USE;
        }
        return DisputeCategory.UNAUTHORIZED_TRANSACTION;
    }

    private void saveChannelNote(UUID disputeId, AppUser customer, String content) {
        DisputeNote note = new DisputeNote();
        note.setDisputeId(disputeId);
        note.setAuthorId(customer.getId());
        note.setAuthorName(customer.getFullName());
        note.setAuthorRole(UserRole.CUSTOMER);
        note.setMessage(content);
        note.setInternalOnly(false);
        note.setRequiresCustomerReply(false);
        note.setCreatedAt(Instant.now());
        noteRepository.save(note);
    }
}

