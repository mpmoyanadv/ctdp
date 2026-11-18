package za.co.capi.dispute.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import za.co.capi.dispute.application.service.OmnichannelDisputeService;
import za.co.capi.dispute.infrastructure.config.OpenApiConfig;
import za.co.capi.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capi.dispute.infrastructure.security.ClientIpResolver;
import za.co.capi.dispute.presentation.dto.ApiDtos.*;

@RestController
@RequestMapping("/api/v1/omnichannel")
@PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
@Tag(name = "5. Omnichannel Intake Simulator (Staff-Only Call Centre KYC, SMS, WhatsApp & Voice Call)")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class OmnichannelController {

    private final OmnichannelDisputeService omnichannelService;
    private final ClientIpResolver clientIpResolver;

    public OmnichannelController(
            OmnichannelDisputeService omnichannelService,
            ClientIpResolver clientIpResolver) {
        this.omnichannelService = omnichannelService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(
            summary = "Load calling customer profile & credit-bureau details by SA ID",
            description = """
                    Allows Call Centre (`STAFF` or `ADMIN`) agents to load a customer's registered phone number, account number, and credit-bureau indicators by SA ID number so they can verify them with the caller whilst on the call.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer KYC and bureau summary returned"),
            @ApiResponse(responseCode = "400", description = "SA ID number missing"),
            @ApiResponse(responseCode = "403", description = "Non-staff role rejected"),
            @ApiResponse(responseCode = "404", description = "No retail customer matching the supplied SA ID number")
    })
    @GetMapping("/lookup-customer")
    public ResponseEntity<CustomerKycLookupResponse> lookupCustomerBySaId(
            @RequestParam("saIdNumber") String saIdNumber,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = clientIpResolver.resolveClientIp(servletRequest);
        return ResponseEntity.ok(omnichannelService.lookupCustomerBySaId(saIdNumber, principal, ip));
    }

    @Operation(
            summary = "Record telephone verification failure (Unverified)",
            description = """
                    Records an immutable `HIGH` severity `CALLER_KYC_VERIFICATION_FAILED` audit event when a Call Centre agent marks a caller as Unverified.
                    """
    )
    @PostMapping("/not-verified")
    public ResponseEntity<java.util.Map<String, Object>> markCallerNotVerified(
            @Valid @RequestBody CallerNotVerifiedRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = clientIpResolver.resolveClientIp(servletRequest);
        return ResponseEntity.ok(omnichannelService.recordCallerVerificationFailed(request, principal, ip));
    }

    @Operation(
            summary = "Verify calling customer identity via SA ID and security/credit-bureau checks",
            description = """
                    Allows Call Centre (`STAFF` or `ADMIN`) agents to verify a calling customer using their SA ID number plus at least two secondary answers (cellphone number, Global One account number/last 4 digits, active Capi Credit Card facility, or active DebiCheck debit order mandate).
                    Upon verification, returns the caller's masked-safe profile summary and bank transactions so the staff member can log a transaction dispute on their behalf.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Caller verified; customer profile and transactions returned"),
            @ApiResponse(responseCode = "400", description = "SA ID missing or fewer than 2 secondary verification questions answered"),
            @ApiResponse(responseCode = "403", description = "Non-staff role rejected or KYC / credit-bureau answer mismatch"),
            @ApiResponse(responseCode = "404", description = "No customer found matching the supplied SA ID number")
    })
    @PostMapping("/verify-customer")
    public ResponseEntity<CustomerCallerVerificationResponse> verifyCustomerCaller(
            @Valid @RequestBody CustomerCallerVerificationRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = clientIpResolver.resolveClientIp(servletRequest);
        return ResponseEntity.ok(omnichannelService.verifyCustomerCaller(request, principal, ip));
    }

    @Operation(
            summary = "Simulate inbound SMS or WhatsApp dispute message",
            description = """
                    Simulates a customer texting or sending a WhatsApp message (for example at an ATM or filling station) to dispute a transaction.
                    Automatically matches `TXN-YYYY-XXXX` references in the message text, infers dispute category, enforces Redis idempotency locking, emits a Kafka `DISPUTE_CREATED` event, sends an email/SMS confirmation receipt, and returns the simulated auto-reply.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Omnichannel SMS/WhatsApp dispute logged and auto-reply generated"),
            @ApiResponse(responseCode = "403", description = "IDOR boundary violation if simulating with another customer's MSISDN or transaction"),
            @ApiResponse(responseCode = "409", description = "Duplicate SMS/WhatsApp webhook retry prevented by Redis idempotency lock")
    })
    @PostMapping("/sms-webhook")
    public ResponseEntity<OmnichannelSimulationResponse> simulateSmsOrWhatsapp(
            @Valid @RequestBody SmsWebhookSimulationRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = clientIpResolver.resolveClientIp(servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(omnichannelService.simulateSmsOrWhatsapp(request, principal, ip));
    }

    @Operation(
            summary = "Simulate inbound Voice Call / Call Centre IVR dispute intake",
            description = """
                    Simulates a customer calling the Fraud / Dispute Call Centre from an ATM, filling station, or after spotting suspicious account activity.
                    Records a structured Call ID, IVR phone verification metadata, optional precautionary card freeze flag, call transcript note, Kafka lifecycle/notification events, and confirmation receipt.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Voice call dispute logged with transcript and confirmation"),
            @ApiResponse(responseCode = "403", description = "IDOR boundary violation"),
            @ApiResponse(responseCode = "409", description = "Duplicate call intake prevented by Redis idempotency lock")
    })
    @PostMapping("/voice-call")
    public ResponseEntity<OmnichannelSimulationResponse> simulateVoiceCall(
            @Valid @RequestBody VoiceCallSimulationRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest servletRequest) {
        String ip = clientIpResolver.resolveClientIp(servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(omnichannelService.simulateVoiceCall(request, principal, ip));
    }
}

