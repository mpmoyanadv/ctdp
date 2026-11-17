package za.co.capi.dispute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import za.co.capi.dispute.domain.model.AuditLog;
import za.co.capi.dispute.domain.model.FraudAlertToken;
import za.co.capi.dispute.domain.model.PasswordResetOtp;
import za.co.capi.dispute.domain.repository.AuditLogRepository;
import za.co.capi.dispute.domain.repository.FraudAlertTokenRepository;
import za.co.capi.dispute.domain.repository.PasswordResetOtpRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
class DisputePortalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private FraudAlertTokenRepository fraudAlertTokenRepository;

    @Autowired
    private PasswordResetOtpRepository passwordResetOtpRepository;

    @MockBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setupMailMock() {
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    @Test
    void fullEndToEndSecurityAndDisputeLifecycle() throws Exception {
        // 1. Customer 1 logs in and creates a dispute with 2 evidence attachments
        String customer1Token = loginAndExtractToken("customer@capi.test", "Password123!");

        MockMultipartFile pdfFile = new MockMultipartFile(
                "files", "statement.pdf", "application/pdf", "%PDF-1.4 sample evidence".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile pngFile = new MockMultipartFile(
                "files", "receipt.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        MvcResult createResult = mockMvc.perform(multipart("/api/v1/disputes")
                        .file(pdfFile)
                        .file(pngFile)
                        .param("merchantName", "Shell Braamfontein")
                        .param("paymentRail", "CARD_POS")
                        .param("category", "UNAUTHORIZED_TRANSACTION")
                        .param("amount", "850.00")
                        .param("reason", "Card was in my possession when this fuel transaction occurred.")
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("LOGGED"))
                .andExpect(jsonPath("$.attachments.length()").value(2))
                .andExpect(jsonPath("$.attachments[0].checksumSha256").isNotEmpty())
                .andReturn();

        JsonNode createdDispute = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String disputeId = createdDispute.get("id").asText();
        String originalRef = createdDispute.get("disputeReference").asText();

        // 1b. Redis Idempotency Lock: Duplicate submission with same payload/idempotency key must be blocked with 409 Conflict
        mockMvc.perform(multipart("/api/v1/disputes")
                        .file(pdfFile)
                        .param("merchantName", "Shell Braamfontein")
                        .param("paymentRail", "CARD_POS")
                        .param("category", "UNAUTHORIZED_TRANSACTION")
                        .param("amount", "850.00")
                        .param("reason", "Card was in my possession when this fuel transaction occurred.")
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Duplicate dispute submission prevented by Redis idempotency lock")));

        List<AuditLog> idempotencyLogs = auditLogRepository.findByEventTypeOrderByTimestampDesc("IDEMPOTENCY_DUPLICATE_PREVENTED");
        assertThat(idempotencyLogs).isNotEmpty();

        // 2. Customer 2 attempts Cross-Customer IDOR access -> 403 + UNAUTHORIZED_ACCESS_ATTEMPT audit log
        String customer2Token = loginAndExtractToken("customer2@capi.test", "Password123!");
        mockMvc.perform(get("/api/v1/disputes/" + disputeId)
                        .header("Authorization", "Bearer " + customer2Token))
                .andExpect(status().isForbidden());

        List<AuditLog> idorLogs = auditLogRepository.findByEventTypeOrderByTimestampDesc("UNAUTHORIZED_ACCESS_ATTEMPT");
        assertThat(idorLogs).isNotEmpty();
        assertThat(idorLogs.getFirst().getSeverity()).isEqualTo("HIGH");
        assertThat(idorLogs.getFirst().getEntityId()).isEqualTo(disputeId);

        // 3. Fraud Alert Lockdown + Session Invalidation + 6-digit OTP Reset
        List<FraudAlertToken> alertTokens = fraudAlertTokenRepository.findAll();
        FraudAlertToken customer1Alert = alertTokens.getFirst();

        mockMvc.perform(post("/api/v1/auth/report-fraud")
                        .param("token", customer1Alert.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true));

        // Existing JWT must immediately be rejected
        mockMvc.perform(get("/api/v1/disputes")
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isUnauthorized());

        // Login attempt while locked must return generic 401 Unauthorized to prevent account enumeration
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@capi.test\",\"password\":\"Password123!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));

        // Unlock with OTP extracted from emailed notification (DB stores only hash)
        PasswordResetOtp otp = passwordResetOtpRepository.findAll().getFirst();
        assertThat(otp.getOtpHash()).startsWith("$2");
        ArgumentCaptor<MimeMessage> mailCaptor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender, atLeastOnce()).send(mailCaptor.capture());
        String otpCode = extractOtpFromEmailBodies(mailCaptor.getAllValues());

        mockMvc.perform(post("/api/v1/auth/reset-password-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"customer@capi.test","otpCode":"%s","newPassword":"Password123!"}
                                """.formatted(otpCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isLocked").value(false));

        customer1Token = loginAndExtractToken("customer@capi.test", "Password123!");

        // 4. Staff claims, requests customer reply, then escalates
        String staffToken = loginAndExtractToken("staff@capi.test", "Password123!");
        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/claim")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/notes")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Please upload police affidavit\",\"internalOnly\":false,\"requiresCustomerReply\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CUSTOMER_ACTION"));

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/escalate")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"High-value card dispute escalated to supervisor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ESCALATED"));

        // 5. Delegate seeded PayShap dispute to PARTNER_BANKSERV and verify Affiliate PII masking
        MvcResult allDisputesRes = mockMvc.perform(get("/api/v1/disputes")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listNode = objectMapper.readTree(allDisputesRes.getResponse().getContentAsString()).get("content");
        String payshapId = null;
        for (JsonNode n : listNode) {
            if ("DSP-2026-10042".equals(n.get("disputeReference").asText())) {
                payshapId = n.get("id").asText();
            }
        }
        assertThat(payshapId).isNotNull();

        mockMvc.perform(post("/api/v1/disputes/" + payshapId + "/delegate")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerDomain\":\"PARTNER_BANKSERV\",\"note\":\"Verify interbank PayShap settlement\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_EXTERNAL_REVIEW"));

        String affiliateToken = loginAndExtractToken("affiliate@bankserv.test", "Password123!");
        mockMvc.perform(get("/api/v1/disputes/" + payshapId)
                        .header("Authorization", "Bearer " + affiliateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.piiMasked").value(true))
                .andExpect(jsonPath("$.customer.saIdNumber").value("850101****082"));

        // 6. Admin rejects escalated dispute -> Customer files linked dispute -> Admin checks productivity & CSV
        String adminToken = loginAndExtractToken("admin@capi.test", "Password123!");
        mockMvc.perform(patch("/api/v1/disputes/" + disputeId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\",\"resolutionSummary\":\"Police affidavit not received within SLA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.terminal").value(true));

        // Terminal dispute must reject uploads
        mockMvc.perform(multipart("/api/v1/disputes/" + disputeId + "/attachments")
                        .file(pdfFile)
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isConflict());

        // Customer re-opens rejected dispute as linked case
        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/reopen")
                        .header("Authorization", "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SAPS case affidavit now obtained and ready for review.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalDisputeId").value(disputeId))
                .andExpect(jsonPath("$.originalDisputeReference").value(originalRef));

        // Admin productivity & CSV export
        mockMvc.perform(get("/api/v1/admin/reports/productivity")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffSummaries").isArray());

        MvcResult csvExport = mockMvc.perform(get("/api/v1/admin/audit-logs/export")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(csvExport.getResponse().getContentAsString()).contains("UNAUTHORIZED_ACCESS_ATTEMPT");
        assertThat(csvExport.getResponse().getContentAsString()).contains("IDEMPOTENCY_DUPLICATE_PREVENTED");

        // 6b. Verify Apache Kafka Event Stream & Redis Idempotency Telemetry endpoint
        mockMvc.perform(get("/api/v1/admin/events/kafka-stream")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuredTopics.length()").value(3))
                .andExpect(jsonPath("$.totalEventsPublished").value(org.hamcrest.Matchers.greaterThan(5)))
                .andExpect(jsonPath("$.recentEvents[0].topic").isNotEmpty());

        // 7. Verify OpenAPI 3.1 Specification (/v3/api-docs) and Swagger UI (/swagger-ui/index.html)
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Cap Trans Dsp Ptl — Enterprise Banking API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/disputes']").exists());

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());

        // 8. Verify Omnichannel SMS/WhatsApp & Voice Call Simulators (ATM & Filling Station scenarios)
        String smsPayload = """
                {
                  "channel": "SMS",
                  "fromPhoneNumber": "+27825550192",
                  "messageBody": "DISPUTE TXN-2026-9005 Sandton ATM did not dispense cash",
                  "idempotencyKey": "sms-atm-retry-001"
                }
                """;

        mockMvc.perform(post("/api/v1/omnichannel/sms-webhook")
                        .header("Authorization", "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("SMS"))
                .andExpect(jsonPath("$.dispute.sourceChannel").value("SMS"))
                .andExpect(jsonPath("$.dispute.category").value("ATM_DISPENSE_ERROR"))
                .andExpect(jsonPath("$.outboundReply").value(org.hamcrest.Matchers.containsString("SMS Auto-Reply")));

        // Duplicate SMS gateway webhook retry must hit Redis idempotency lock -> 409 Conflict
        mockMvc.perform(post("/api/v1/omnichannel/sms-webhook")
                        .header("Authorization", "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsPayload))
                .andExpect(status().isConflict());

        // Voice Call Call-Centre simulator (Filling station scenario)
        mockMvc.perform(post("/api/v1/omnichannel/voice-call")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "callerPhoneNumber": "+27825550192",
                                  "transactionReference": "TXN-2026-9001",
                                  "scenario": "FILLING_STATION_DOUBLE_TAP",
                                  "category": "FRAUDULENT_CARD_USE",
                                  "callerStatement": "Customer called from Shell Braamfontein stating card was tapped twice.",
                                  "agentNotes": "IVR verified; customer retained fuel slip.",
                                  "cardFreezeRequested": true,
                                  "idempotencyKey": "voice-fuel-001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("PHONE_CALL"))
                .andExpect(jsonPath("$.dispute.sourceChannel").value("PHONE_CALL"))
                .andExpect(jsonPath("$.dispute.notes[0].message").value(org.hamcrest.Matchers.containsString("VOICE CALL RECORDING")));
    }

    private String loginAndExtractToken(String email, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String extractOtpFromEmailBodies(List<MimeMessage> messages) throws Exception {
        Pattern pattern = Pattern.compile("id=\"otp-code\"[^>]*>(\\d{6})</span>");
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object content = messages.get(i).getContent();
            String body = content != null ? content.toString() : "";
            Matcher matcher = pattern.matcher(body);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        throw new IllegalStateException("No 6-digit OTP found in dispatched email bodies");
    }
}

