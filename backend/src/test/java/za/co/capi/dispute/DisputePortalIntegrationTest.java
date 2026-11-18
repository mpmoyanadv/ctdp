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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.AuditLog;
import za.co.capi.dispute.domain.model.FraudAlertToken;
import za.co.capi.dispute.domain.model.PasswordResetOtp;
import za.co.capi.dispute.domain.repository.AppUserRepository;
import za.co.capi.dispute.domain.repository.AuditLogRepository;
import za.co.capi.dispute.domain.repository.FraudAlertTokenRepository;
import za.co.capi.dispute.domain.repository.PasswordResetOtpRepository;
import za.co.capi.dispute.infrastructure.cache.LoginRateLimiterService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DisputePortalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private FraudAlertTokenRepository fraudAlertTokenRepository;

    @Autowired
    private PasswordResetOtpRepository passwordResetOtpRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LoginRateLimiterService loginRateLimiterService;

    @MockBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setupTestState() {
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        loginRateLimiterService.clearLocalLimits();
        AppUser customer = appUserRepository.findByEmailIgnoreCase("customer@capi.test").orElseThrow();
        customer.setLocked(false);
        customer.setLockedReason(null);
        customer.setPasswordHash(passwordEncoder.encode("Password123!"));
        appUserRepository.save(customer);

        AppUser customer2 = appUserRepository.findByEmailIgnoreCase("customer2@capi.test").orElseThrow();
        customer2.setLocked(false);
        customer2.setLockedReason(null);
        customer2.setPasswordHash(passwordEncoder.encode("Password123!"));
        appUserRepository.save(customer2);
    }

    @Test
    void createsDisputeWithAttachmentsAndEnforcesIdempotencyAndIdor() throws Exception {
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

        String disputeId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // Redis Idempotency Lock blocks duplicate submission -> 409 Conflict
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

        assertThat(auditLogRepository.findByEventTypeOrderByTimestampDesc("IDEMPOTENCY_DUPLICATE_PREVENTED")).isNotEmpty();

        // Customer 2 attempts Cross-Customer IDOR access -> 403 + UNAUTHORIZED_ACCESS_ATTEMPT audit log
        String customer2Token = loginAndExtractToken("customer2@capi.test", "Password123!");
        mockMvc.perform(get("/api/v1/disputes/" + disputeId)
                        .header("Authorization", "Bearer " + customer2Token))
                .andExpect(status().isForbidden());

        List<AuditLog> idorLogs = auditLogRepository.findByEventTypeOrderByTimestampDesc("UNAUTHORIZED_ACCESS_ATTEMPT");
        assertThat(idorLogs).isNotEmpty();
        assertThat(idorLogs.getFirst().getSeverity()).isEqualTo("HIGH");
        assertThat(idorLogs.getFirst().getEntityId()).isEqualTo(disputeId);
    }

    @Test
    void locksAccountOnFraudReportAndRestoresAccessViaSixDigitOtp() throws Exception {
        String customer1Token = loginAndExtractToken("customer@capi.test", "Password123!");

        FraudAlertToken customer1Alert = fraudAlertTokenRepository.findAll().stream()
                .filter(t -> !t.isUsed())
                .reduce((first, second) -> second)
                .orElseThrow();

        mockMvc.perform(post("/api/v1/auth/report-fraud")
                        .param("token", customer1Alert.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true));

        // Existing JWT must immediately be rejected
        mockMvc.perform(get("/api/v1/disputes")
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isUnauthorized());

        // Login attempt while locked must return generic 401 Unauthorized
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@capi.test\",\"password\":\"Password123!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));

        PasswordResetOtp otp = passwordResetOtpRepository.findAll().getLast();
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

        String freshToken = loginAndExtractToken("customer@capi.test", "Password123!");
        assertThat(freshToken).isNotBlank();
    }

    @Test
    void supportsClaimCustomerReplyEscalationPartnerDelegationAndLinkedReopen() throws Exception {
        String customer1Token = loginAndExtractToken("customer@capi.test", "Password123!");
        String staffToken = loginAndExtractToken("staff@capi.test", "Password123!");
        String adminToken = loginAndExtractToken("admin@capi.test", "Password123!");
        String affiliateToken = loginAndExtractToken("affiliate@bankserv.test", "Password123!");

        MockMultipartFile pdfFile = new MockMultipartFile(
                "files", "statement.pdf", "application/pdf", "%PDF-1.4 sample evidence".getBytes(StandardCharsets.UTF_8));

        MvcResult createResult = mockMvc.perform(multipart("/api/v1/disputes")
                        .file(pdfFile)
                        .param("merchantName", "Lifecycle Merchant")
                        .param("paymentRail", "CARD_POS")
                        .param("category", "UNAUTHORIZED_TRANSACTION")
                        .param("amount", "950.00")
                        .param("reason", "Testing claim, customer reply, escalation, rejection, and linked reopen.")
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createdDispute = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String disputeId = createdDispute.get("id").asText();
        String originalRef = createdDispute.get("disputeReference").asText();

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

        // Delegate seeded PayShap dispute to PARTNER_BANKSERV and verify Affiliate PII masking
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

        // Unassigned PayShap dispute cannot be delegated by staff until Admin assigns it
        mockMvc.perform(post("/api/v1/disputes/" + payshapId + "/delegate")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerDomain\":\"PARTNER_BANKSERV\",\"note\":\"Verify interbank PayShap settlement\"}"))
                .andExpect(status().isForbidden());

        AppUser staffUser = appUserRepository.findByEmailIgnoreCase("staff@capi.test").orElseThrow();
        mockMvc.perform(post("/api/v1/admin/disputes/" + payshapId + "/assign")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeUserId\":\"" + staffUser.getId() + "\",\"assignedDomain\":\"INTERNAL_CAPI\",\"status\":\"IN_REVIEW\",\"note\":\"Assigned to Sipho\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedUserName").value("Sipho Ndlovu"));

        mockMvc.perform(post("/api/v1/disputes/" + payshapId + "/delegate")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerDomain\":\"PARTNER_BANKSERV\",\"note\":\"Verify interbank PayShap settlement\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_EXTERNAL_REVIEW"));

        mockMvc.perform(get("/api/v1/disputes/" + payshapId)
                        .header("Authorization", "Bearer " + affiliateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.piiMasked").value(true))
                .andExpect(jsonPath("$.customer.saIdNumber").value("850101****082"));

        // Admin rejects escalated dispute -> terminal upload blocked -> customer reopens as linked case
        mockMvc.perform(patch("/api/v1/disputes/" + disputeId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\",\"resolutionSummary\":\"Police affidavit not received within SLA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.terminal").value(true));

        mockMvc.perform(multipart("/api/v1/disputes/" + disputeId + "/attachments")
                        .file(pdfFile)
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/reopen")
                        .header("Authorization", "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SAPS case affidavit now obtained and ready for review.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalDisputeId").value(disputeId))
                .andExpect(jsonPath("$.originalDisputeReference").value(originalRef));
    }

    @Test
    void exposesAdminAuditCsvProductivityKafkaTelemetryAndOpenApiSpec() throws Exception {
        String customer1Token = loginAndExtractToken("customer@capi.test", "Password123!");
        String customer2Token = loginAndExtractToken("customer2@capi.test", "Password123!");
        String adminToken = loginAndExtractToken("admin@capi.test", "Password123!");

        // Trigger a fresh dispute and IDOR audit record so assertions are order-independent
        MvcResult created = mockMvc.perform(post("/api/v1/disputes")
                        .header("Authorization", "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantName\":\"Telemetry Shop\",\"paymentRail\":\"CARD_POS\",\"category\":\"UNAUTHORIZED_TRANSACTION\",\"amount\":199.00,\"reason\":\"Admin telemetry integration check.\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String disputeId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/disputes/" + disputeId)
                        .header("Authorization", "Bearer " + customer2Token))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/reports/productivity")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffSummaries").isArray());

        MvcResult csvExport = mockMvc.perform(get("/api/v1/admin/audit-logs/export")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(csvExport.getResponse().getContentAsString()).contains("UNAUTHORIZED_ACCESS_ATTEMPT");

        mockMvc.perform(get("/api/v1/admin/events/kafka-stream")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuredTopics.length()").value(3))
                .andExpect(jsonPath("$.totalEventsPublished").value(org.hamcrest.Matchers.greaterThan(1)))
                .andExpect(jsonPath("$.recentEvents[0].topic").isNotEmpty());

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Cap Trans Dsp Ptl — Enterprise Banking API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/disputes']").exists());

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void processesOmnichannelSmsAndVoiceCallDisputeIntakes() throws Exception {
        String customer1Token = loginAndExtractToken("customer@capi.test", "Password123!");
        String staffToken = loginAndExtractToken("staff@capi.test", "Password123!");

        String smsPayload = """
                {
                  "channel": "SMS",
                  "fromPhoneNumber": "+27825550192",
                  "messageBody": "DISPUTE TXN-2026-9005 Sandton ATM did not dispense cash",
                  "idempotencyKey": "sms-atm-retry-001"
                }
                """;

        // Customers cannot invoke staff-only omnichannel simulation endpoints
        mockMvc.perform(post("/api/v1/omnichannel/sms-webhook")
                        .header("Authorization", "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsPayload))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/omnichannel/sms-webhook")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("SMS"))
                .andExpect(jsonPath("$.dispute.sourceChannel").value("SMS"))
                .andExpect(jsonPath("$.dispute.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.dispute.assignedUserName").value("Sipho Ndlovu"))
                .andExpect(jsonPath("$.dispute.category").value("ATM_DISPENSE_ERROR"))
                .andExpect(jsonPath("$.outboundReply").value(org.hamcrest.Matchers.containsString("SMS Auto-Reply")));

        mockMvc.perform(post("/api/v1/omnichannel/sms-webhook")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsPayload))
                .andExpect(status().isConflict());

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
                .andExpect(jsonPath("$.dispute.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.dispute.assignedUserName").value("Sipho Ndlovu"))
                .andExpect(jsonPath("$.dispute.notes[0].message").value(org.hamcrest.Matchers.containsString("VOICE CALL RECORDING")));
    }

    @Test
    void listsUsersAndAllowsAdminToUnlockLockedUserAccount() throws Exception {
        String customer1Token = loginAndExtractToken("customer@capi.test", "Password123!");
        String adminToken = loginAndExtractToken("admin@capi.test", "Password123!");
        AppUser customer2 = appUserRepository.findByEmailIgnoreCase("customer2@capi.test").orElseThrow();

        // Non-admin users cannot access admin user directory
        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)));

        // Lock customer2 via 5 consecutive failed login attempts and flag the account locked
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"customer2@capi.test\",\"password\":\"WrongPass!\"}"))
                    .andExpect(status().isUnauthorized());
        }
        customer2.setLocked(true);
        customer2.setLockedReason("Locked during admin unlock test");
        appUserRepository.save(customer2);

        // Even with the valid password, locked/throttled login must fail
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer2@capi.test\",\"password\":\"Password123!\"}"))
                .andExpect(status().isUnauthorized());

        // Admin unlocks customer2 (clears both DB lock and Redis/local brute-force counter)
        mockMvc.perform(post("/api/v1/admin/users/" + customer2.getId() + "/unlock")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("customer2@capi.test"))
                .andExpect(jsonPath("$.isLocked").value(false));

        // Login succeeds again after unlock and audit record is persisted
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer2@capi.test\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        assertThat(auditLogRepository.findAllByOrderByTimestampDesc().stream()
                .map(AuditLog::getEventType))
                .contains("ADMIN_UNLOCKED_USER_ACCOUNT");

        // Admin role catalog + create/update role + create/update staff user + productivity integration
        mockMvc.perform(get("/api/v1/admin/roles")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(6)));

        mockMvc.perform(post("/api/v1/admin/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleCode": "CHARGEBACK_LEAD",
                                  "displayName": "Chargeback Team Lead",
                                  "baseRole": "STAFF",
                                  "defaultDomain": "INTERNAL_CAPI",
                                  "description": "Leads chargeback escalations."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roleCode").value("CHARGEBACK_LEAD"))
                .andExpect(jsonPath("$.displayName").value("Chargeback Team Lead"));

        mockMvc.perform(put("/api/v1/admin/roles/CHARGEBACK_LEAD")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Senior Chargeback Lead",
                                  "baseRole": "STAFF",
                                  "defaultDomain": "INTERNAL_CAPI",
                                  "description": "Leads high-value chargeback escalations."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Senior Chargeback Lead"));

        MvcResult createdStaffRes = mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Kagiso Molefe",
                                  "email": "kagiso.molefe@capi.test",
                                  "phoneNumber": "+27219411055",
                                  "role": "STAFF",
                                  "userDomain": "INTERNAL_CAPI",
                                  "partnerInstitution": "Chargeback Specialist"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("kagiso.molefe@capi.test"))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.partnerInstitution").value("Chargeback Specialist"))
                .andReturn();
        String newStaffId = objectMapper.readTree(createdStaffRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(put("/api/v1/admin/users/" + newStaffId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Kagiso Molefe-Ndlovu",
                                  "email": "kagiso.molefe@capi.test",
                                  "phoneNumber": "+27219411056",
                                  "role": "ADMIN",
                                  "userDomain": "INTERNAL_CAPI",
                                  "partnerInstitution": "Senior Chargeback Lead",
                                  "isLocked": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Kagiso Molefe-Ndlovu"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.partnerInstitution").value("Senior Chargeback Lead"));

        MvcResult prodRes = mockMvc.perform(get("/api/v1/admin/reports/productivity")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentSessions[0].assignmentSource").isNotEmpty())
                .andExpect(jsonPath("$.recentSessions[0].sessionOutcome").isNotEmpty())
                .andReturn();
        assertThat(prodRes.getResponse().getContentAsString())
                .contains("kagiso.molefe@capi.test")
                .contains("Senior Chargeback Lead");

        assertThat(auditLogRepository.findAllByOrderByTimestampDesc().stream()
                .map(AuditLog::getEventType))
                .contains("ADMIN_ROLE_CREATED", "ADMIN_ROLE_UPDATED", "ADMIN_STAFF_USER_CREATED", "ADMIN_STAFF_USER_UPDATED");
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
