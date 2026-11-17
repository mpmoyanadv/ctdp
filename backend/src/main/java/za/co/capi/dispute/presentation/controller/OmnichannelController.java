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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import za.co.capi.dispute.application.service.OmnichannelDisputeService;
import za.co.capi.dispute.infrastructure.config.OpenApiConfig;
import za.co.capi.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capi.dispute.infrastructure.security.ClientIpResolver;
import za.co.capi.dispute.presentation.dto.ApiDtos.*;

@RestController
@RequestMapping("/api/v1/omnichannel")
@Tag(name = "5. Omnichannel Intake Simulator (SMS, WhatsApp & Voice Call)")
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

