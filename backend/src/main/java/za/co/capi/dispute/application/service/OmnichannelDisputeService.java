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
    private final PiiMaskingService piiMaskingService;

    public OmnichannelDisputeService(
            AppUserRepository userRepository,
            BankTransactionRepository transactionRepository,
            DisputeNoteRepository noteRepository,
            DisputeService disputeService,
            NotificationEmailService notificationEmailService,
            AuditService auditService,
            PiiMaskingService piiMaskingService) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.noteRepository = noteRepository;
        this.disputeService = disputeService;
        this.notificationEmailService = notificationEmailService;
        this.auditService = auditService;
        this.piiMaskingService = piiMaskingService;
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
        String merchant = tx.getMerchantName();
        String rail = tx.getPaymentRail();
        BigDecimal amount = tx.getAmount();

        String maskedPhone = piiMaskingService.maskPhone(customer.getPhoneNumber());
        String metadata = channel.name() + " Gateway | Matched: " + tx.getTransactionReference();

        CreateDisputeRequest createReq = new CreateDisputeRequest(
                tx.getId(),
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
                metadata,
                actor.getId()
        );

        String transcript = "[" + channel.name() + " INBOUND (" + maskedPhone + ")]: \"" + request.messageBody().trim() + "\"";
        saveInternalChannelNote(created.id(), customer, transcript);

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
        boolean hasExplicitTx = request.transactionId() != null
                || (request.transactionReference() != null && !request.transactionReference().isBlank())
                || (request.callerStatement() != null && TXN_REF_PATTERN.matcher(request.callerStatement()).find());
        boolean hasManualCapture = request.amount() != null
                && request.merchantName() != null
                && !request.merchantName().isBlank();

        BankTransaction tx = (hasExplicitTx || !hasManualCapture)
                ? resolveTransaction(
                        customer.getId(),
                        request.transactionId(),
                        request.transactionReference() != null ? request.transactionReference() : request.callerStatement(),
                        actor,
                        ipAddress
                )
                : null;

        DisputeCategory category = request.category() != null
                ? request.category()
                : inferCategoryFromMessage(request.callerStatement(), tx);

        String merchant = tx != null ? tx.getMerchantName() : request.merchantName().trim();
        String rail = request.paymentRail() != null && !request.paymentRail().isBlank()
                ? request.paymentRail().trim()
                : (tx != null ? tx.getPaymentRail() : "CARD_POS");
        BigDecimal amount = tx != null ? tx.getAmount() : request.amount();
        String locationInfo = request.location() != null && !request.location().isBlank()
                ? request.location().trim()
                : (tx != null && tx.getLocation() != null && !tx.getLocation().isBlank() ? tx.getLocation().trim() : null);

        String callId = "CALL-" + (100000 + RANDOM.nextInt(900000));
        String maskedPhone = piiMaskingService.maskPhone(customer.getPhoneNumber());
        String scenarioTag = request.scenario() != null && !request.scenario().isBlank()
                ? request.scenario().trim()
                : "CUSTOMER_VOICE_REPORT";

        String metadata = callId + " | Scenario: " + scenarioTag
                + " | Type: " + rail
                + (tx != null ? " | Matched: " + tx.getTransactionReference() : " | Unlinked Assisted Intake")
                + (locationInfo != null ? " | Location: " + locationInfo : "")
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
                metadata,
                actor.getId()
        );

        StringBuilder transcript = new StringBuilder();
        transcript.append("[VOICE CALL RECORDING ").append(callId).append(" | IVR Caller Verified (").append(maskedPhone).append(")]\n")
                .append("Scenario: ").append(scenarioTag).append("\n")
                .append("Transaction Type: ").append(rail).append("\n");
        if (locationInfo != null) {
            transcript.append("Location: ").append(locationInfo).append("\n");
        }
        transcript.append("Customer Statement: ").append(request.callerStatement().trim());
        if (request.agentNotes() != null && !request.agentNotes().isBlank()) {
            transcript.append("\nAgent Notes: ").append(request.agentNotes().trim());
        }
        if (request.cardFreezeRequested()) {
            transcript.append("\nAction Taken: Temporary precautionary card freeze flag requested during call.");
        }

        saveInternalChannelNote(created.id(), customer, transcript.toString());

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
                transcript.toString()
        );

        DisputeDetailDto refreshed = disputeService.refreshDetailDto(created.id(), actor.getRole());
        return new OmnichannelSimulationResponse(refreshed, DisputeChannel.PHONE_CALL, outboundReply, transcript.toString(), Instant.now());
    }

    @Transactional
    public CustomerKycLookupResponse lookupCustomerBySaId(
            String saIdNumber,
            AppUserPrincipal actor,
            String ipAddress) {
        requireStaffOrAdmin(actor, ipAddress, "CALLER_KYC_LOOKUP");
        AppUser customer = findRetailCustomerForKyc(saIdNumber, "CALLER_KYC_LOOKUP_FAILED", actor, ipAddress);

        List<BankTransaction> customerTransactions = transactionRepository.findByCustomerIdOrderByTransactedAtDesc(customer.getId());
        boolean actualCreditCard = hasCreditCardProfile(customer, customerTransactions);
        boolean actualDebitOrder = hasActiveDebitOrder(customerTransactions);
        String bureauSummary = buildBureauSummary(actualCreditCard, actualDebitOrder);

        Map<String, Object> lookupMetadata = new java.util.LinkedHashMap<>();
        lookupMetadata.put("action", "LOAD_CUSTOMER_DETAILS");
        lookupMetadata.put("saIdNumber", safeString(customer.getSaIdNumber(), ""));
        lookupMetadata.put("customerFullName", safeString(customer.getFullName(), "UNKNOWN"));
        lookupMetadata.put("customerEmail", safeString(customer.getEmail(), "UNKNOWN"));
        lookupMetadata.put("accountNumber", safeString(customer.getAccountNumber(), ""));
        lookupMetadata.put("staffEmail", actor.getEmail());
        lookupMetadata.put("staffRole", actor.getRole().name());

        auditService.logEvent(
                actor.getId(),
                actor.getEmail(),
                "INFO",
                "CALLER_KYC_LOOKUP",
                "AppUser",
                customer.getId().toString(),
                ipAddress,
                lookupMetadata
        );

        return new CustomerKycLookupResponse(
                customer.getId(),
                customer.getFullName(),
                customer.getEmail(),
                customer.getSaIdNumber(),
                customer.getPhoneNumber(),
                customer.getAccountNumber(),
                actualCreditCard,
                actualDebitOrder,
                bureauSummary
        );
    }

    @Transactional
    public Map<String, Object> recordCallerVerificationFailed(
            CallerNotVerifiedRequest request,
            AppUserPrincipal actor,
            String ipAddress) {
        requireStaffOrAdmin(actor, ipAddress, "CALLER_KYC_VERIFICATION_FAILED");

        String saId = request.saIdNumber() != null ? request.saIdNumber().trim() : "";
        AppUser matchedUser = userRepository.findFirstBySaIdNumberIgnoreCase(saId).orElse(null);
        String targetId = matchedUser != null
                ? matchedUser.getId().toString()
                : (saId.isBlank() ? "UNKNOWN" : saId);
        String reason = request.reason() != null && !request.reason().isBlank()
                ? request.reason().trim()
                : "Staff marked caller as Unverified during telephone KYC check";

        Map<String, Object> unverifiedMetadata = new java.util.LinkedHashMap<>();
        unverifiedMetadata.put("decision", "UNVERIFIED");
        unverifiedMetadata.put("saIdNumber", saId);
        unverifiedMetadata.put("customerFullName", matchedUser != null ? safeString(matchedUser.getFullName(), "UNKNOWN") : "UNKNOWN");
        unverifiedMetadata.put("customerEmail", matchedUser != null ? safeString(matchedUser.getEmail(), "UNKNOWN") : "UNKNOWN");
        unverifiedMetadata.put("staffEmail", actor.getEmail());
        unverifiedMetadata.put("staffRole", actor.getRole().name());
        unverifiedMetadata.put("reason", reason);

        auditService.logEvent(
                actor.getId(),
                actor.getEmail(),
                "HIGH",
                "CALLER_KYC_VERIFICATION_FAILED",
                "AppUser",
                targetId,
                ipAddress,
                unverifiedMetadata
        );

        return Map.of(
                "verified", false,
                "message", "Caller marked as Unverified. Account transactions and dispute capture remain locked."
        );
    }

    @Transactional
    public CustomerCallerVerificationResponse verifyCustomerCaller(
            CustomerCallerVerificationRequest request,
            AppUserPrincipal actor,
            String ipAddress) {
        requireStaffOrAdmin(actor, ipAddress, "CALLER_KYC_VERIFICATION");

        AppUser customer = findRetailCustomerForKyc(request.saIdNumber(), "CALLER_KYC_VERIFICATION_FAILED", actor, ipAddress);

        List<BankTransaction> customerTransactions = transactionRepository.findByCustomerIdOrderByTransactedAtDesc(customer.getId());
        boolean actualCreditCard = hasCreditCardProfile(customer, customerTransactions);
        boolean actualDebitOrder = hasActiveDebitOrder(customerTransactions);

        int answered = 0;
        List<String> failedChecks = new java.util.ArrayList<>();
        List<String> passedChecks = new java.util.ArrayList<>();

        if (request.phoneNumber() != null && !request.phoneNumber().isBlank()) {
            answered++;
            if (normalizePhone(request.phoneNumber()).equals(normalizePhone(customer.getPhoneNumber()))) {
                passedChecks.add("Cellphone Number");
            } else {
                failedChecks.add("Cellphone Number");
            }
        }

        if (request.accountNumber() != null && !request.accountNumber().isBlank()) {
            answered++;
            String providedAcct = request.accountNumber().trim();
            String storedAcct = customer.getAccountNumber() != null ? customer.getAccountNumber().trim() : "";
            boolean matchesAccount = !storedAcct.isBlank()
                    && (storedAcct.equals(providedAcct)
                    || (providedAcct.length() == 4 && storedAcct.endsWith(providedAcct)));
            if (matchesAccount) {
                passedChecks.add("Account Number");
            } else {
                failedChecks.add("Account Number");
            }
        }

        if (request.hasCreditCardFacility() != null) {
            answered++;
            if (request.hasCreditCardFacility().equals(actualCreditCard)) {
                passedChecks.add("Credit Bureau: Credit Card Facility");
            } else {
                failedChecks.add("Credit Bureau: Credit Card Facility");
            }
        }

        if (request.hasActiveDebitOrderMandate() != null) {
            answered++;
            if (request.hasActiveDebitOrderMandate().equals(actualDebitOrder)) {
                passedChecks.add("Credit Bureau / Mandate: DebiCheck Debit Order");
            } else {
                failedChecks.add("Credit Bureau / Mandate: DebiCheck Debit Order");
            }
        }

        if (answered < 2) {
            Map<String, Object> insufficientMetadata = new java.util.LinkedHashMap<>();
            insufficientMetadata.put("decision", "INSUFFICIENT_CHECKS");
            insufficientMetadata.put("saIdNumber", safeString(customer.getSaIdNumber(), ""));
            insufficientMetadata.put("customerFullName", safeString(customer.getFullName(), "UNKNOWN"));
            insufficientMetadata.put("customerEmail", safeString(customer.getEmail(), "UNKNOWN"));
            insufficientMetadata.put("questionsAnswered", answered);
            insufficientMetadata.put("staffEmail", actor.getEmail());
            auditService.logEvent(
                    actor.getId(),
                    actor.getEmail(),
                    "WARN",
                    "CALLER_KYC_VERIFICATION_FAILED",
                    "AppUser",
                    customer.getId().toString(),
                    ipAddress,
                    insufficientMetadata
            );
            throw ApiException.badRequest(
                    "Please answer at least 2 secondary security/credit-bureau verification questions alongside the SA ID number.");
        }

        if (!failedChecks.isEmpty()) {
            Map<String, Object> mismatchMetadata = new java.util.LinkedHashMap<>();
            mismatchMetadata.put("decision", "UNVERIFIED_MISMATCH");
            mismatchMetadata.put("saIdNumber", safeString(customer.getSaIdNumber(), ""));
            mismatchMetadata.put("customerFullName", safeString(customer.getFullName(), "UNKNOWN"));
            mismatchMetadata.put("customerEmail", safeString(customer.getEmail(), "UNKNOWN"));
            mismatchMetadata.put("questionsAnswered", answered);
            mismatchMetadata.put("failedChecks", String.join(", ", failedChecks));
            mismatchMetadata.put("staffEmail", actor.getEmail());
            auditService.logEvent(
                    actor.getId(),
                    actor.getEmail(),
                    "HIGH",
                    "CALLER_KYC_VERIFICATION_FAILED",
                    "AppUser",
                    customer.getId().toString(),
                    ipAddress,
                    mismatchMetadata
            );
            throw ApiException.forbidden(
                    "Customer identity verification failed: one or more security or credit-bureau answers did not match.");
        }

        Map<String, Object> verifiedMetadata = new java.util.LinkedHashMap<>();
        verifiedMetadata.put("decision", "VERIFIED");
        verifiedMetadata.put("saIdNumber", safeString(customer.getSaIdNumber(), ""));
        verifiedMetadata.put("customerFullName", safeString(customer.getFullName(), "UNKNOWN"));
        verifiedMetadata.put("customerEmail", safeString(customer.getEmail(), "UNKNOWN"));
        verifiedMetadata.put("accountNumber", safeString(customer.getAccountNumber(), ""));
        verifiedMetadata.put("questionsAnswered", answered);
        verifiedMetadata.put("verifiedChecks", String.join(", ", passedChecks));
        verifiedMetadata.put("staffEmail", actor.getEmail());

        auditService.logEvent(
                actor.getId(),
                actor.getEmail(),
                "INFO",
                "CALLER_KYC_VERIFIED",
                "AppUser",
                customer.getId().toString(),
                ipAddress,
                verifiedMetadata
        );

        String bureauSummary = buildBureauSummary(actualCreditCard, actualDebitOrder);

        CustomerSummaryDto customerDto = new CustomerSummaryDto(
                customer.getId(),
                customer.getFullName(),
                customer.getEmail(),
                customer.getSaIdNumber(),
                customer.getPhoneNumber(),
                customer.getAccountNumber(),
                false
        );

        List<TransactionDto> txDtos = customerTransactions.stream()
                .map(t -> new TransactionDto(
                        t.getId(),
                        t.getTransactionReference(),
                        t.getMerchantName(),
                        t.getPaymentRail(),
                        t.getAmount(),
                        t.getCurrency(),
                        t.getCardLastFour(),
                        t.getLocation(),
                        t.getTransactedAt(),
                        t.getStatus()
                ))
                .toList();

        return new CustomerCallerVerificationResponse(
                true,
                answered,
                "Verified via SA ID + " + answered + " checks (" + String.join(", ", passedChecks) + ")",
                bureauSummary,
                customerDto,
                txDtos
        );
    }

    private AppUser findRetailCustomerForKyc(
            String saIdNumber,
            String failureEventType,
            AppUserPrincipal actor,
            String ipAddress) {
        String saId = saIdNumber != null ? saIdNumber.trim() : "";
        if (saId.isBlank()) {
            auditService.logEvent(
                    actor.getId(),
                    actor.getEmail(),
                    "WARN",
                    failureEventType,
                    "AppUser",
                    "BLANK_SA_ID",
                    ipAddress,
                    Map.of(
                            "attemptedSaIdNumber", "",
                            "staffEmail", actor.getEmail(),
                            "staffRole", actor.getRole().name(),
                            "reason", "Customer lookup/verification failed: SA ID number was blank"
                    )
            );
            throw ApiException.badRequest("SA ID number is required to look up or verify the calling customer.");
        }
        return userRepository.findFirstBySaIdNumberIgnoreCase(saId)
                .filter(u -> u.getRole() == UserRole.CUSTOMER)
                .orElseThrow(() -> {
                    String severity = "CALLER_KYC_LOOKUP_FAILED".equals(failureEventType) ? "WARN" : "HIGH";
                    auditService.logEvent(
                            actor.getId(),
                            actor.getEmail(),
                            severity,
                            failureEventType,
                            "AppUser",
                            saId,
                            ipAddress,
                            Map.of(
                                    "attemptedSaIdNumber", saId,
                                    "staffEmail", actor.getEmail(),
                                    "staffRole", actor.getRole().name(),
                                    "reason", "Customer lookup/verification failed: SA ID or Foreign Passport not found"
                            )
                    );
                    return ApiException.notFound("No retail customer profile found matching the provided SA ID or Foreign Passport number.");
                });
    }

    private String safeString(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private String buildBureauSummary(boolean actualCreditCard, boolean actualDebitOrder) {
        return "Credit Bureau Snapshot: Credit Card Facility=" + (actualCreditCard ? "ACTIVE" : "NONE")
                + " | Active DebiCheck Mandate=" + (actualDebitOrder ? "YES" : "NO");
    }

    private void requireStaffOrAdmin(AppUserPrincipal actor, String ipAddress, String action) {
        if (actor.getRole() != UserRole.STAFF && actor.getRole() != UserRole.ADMIN) {
            auditService.logEvent(
                    actor.getId(),
                    actor.getEmail(),
                    "HIGH",
                    "UNAUTHORIZED_ACCESS_ATTEMPT",
                    "Omnichannel",
                    action,
                    ipAddress,
                    Map.of("role", actor.getRole().name())
            );
            throw ApiException.forbidden("403 Forbidden: Only Call Centre staff or admins can initiate omnichannel intake.");
        }
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("27") && digits.length() == 11) {
            return "0" + digits.substring(2);
        }
        return digits;
    }

    private boolean hasCreditCardProfile(AppUser customer, List<BankTransaction> txs) {
        String idNumber = customer.getSaIdNumber();
        if ("9104120842087".equalsIgnoreCase(idNumber) || "NG7739201".equalsIgnoreCase(idNumber)) {
            return true;
        }
        if ("8501015800082".equalsIgnoreCase(idNumber) || "ZW4829104".equalsIgnoreCase(idNumber)) {
            return false;
        }
        return txs.stream().anyMatch(tx -> "CREDIT_CARD".equalsIgnoreCase(tx.getPaymentRail()));
    }

    private boolean hasActiveDebitOrder(List<BankTransaction> txs) {
        return txs.stream().anyMatch(tx -> "DEBIT_ORDER".equalsIgnoreCase(tx.getPaymentRail()));
    }

    private AppUser resolveTargetCustomer(String phoneNumber, AppUserPrincipal actor, String ipAddress) {
        requireStaffOrAdmin(actor, ipAddress, "OMNICHANNEL_SIMULATION");
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw ApiException.badRequest("Phone number is required for omnichannel dispute intake.");
        }
        String trimmedPhone = phoneNumber.trim();

        return userRepository.findFirstByPhoneNumber(trimmedPhone)
                .orElseThrow(() -> ApiException.notFound("No retail customer registered with the provided phone number."));
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

        throw ApiException.badRequest("Transaction ID or transaction reference (TXN-YYYY-XXXX) is required for omnichannel dispute intake.");
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

    private void saveInternalChannelNote(UUID disputeId, AppUser customer, String content) {
        DisputeNote note = new DisputeNote();
        note.setDisputeId(disputeId);
        note.setAuthorId(customer.getId());
        note.setAuthorName(customer.getFullName());
        note.setAuthorRole(UserRole.CUSTOMER);
        note.setMessage(content);
        note.setInternalOnly(true);
        note.setRequiresCustomerReply(false);
        note.setCreatedAt(Instant.now());
        noteRepository.save(note);
    }
}
