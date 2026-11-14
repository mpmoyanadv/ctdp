package za.co.capi.dispute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.repository.AppUserRepository;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DisputeAuthorizationAndTransitionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository appUserRepository;

    @MockBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setupMailMock() {
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    @Test
    void enforcesRolePermissionsAndCrossDomainBoundaries() throws Exception {
        String customer1Token = loginAndExtractToken("customer@capi.test", "Password123!");
        String customer2Token = loginAndExtractToken("customer2@capi.test", "Password123!");
        String staffToken = loginAndExtractToken("staff@capi.test", "Password123!");
        String affiliateToken = loginAndExtractToken("affiliate@bankserv.test", "Password123!");

        String disputeId = createDispute(customer1Token, "Woolworths Sandton", "1245.50");

        // Cross-customer IDOR must be denied across read and write endpoints
        mockMvc.perform(get("/api/v1/disputes/" + disputeId)
                        .header("Authorization", "Bearer " + customer2Token))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/notes")
                        .header("Authorization", "Bearer " + customer2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Unauthorized note\",\"internalOnly\":false,\"requiresCustomerReply\":false}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/escalate")
                        .header("Authorization", "Bearer " + customer2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Attempting cross-customer escalation\"}"))
                .andExpect(status().isForbidden());

        MockMultipartFile extraFile = new MockMultipartFile(
                "files", "evidence.pdf", "application/pdf", "%PDF-1.4 sample".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/disputes/" + disputeId + "/attachments")
                        .file(extraFile)
                        .header("Authorization", "Bearer " + customer2Token))
                .andExpect(status().isForbidden());

        // Customer cannot perform internal staff actions
        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/claim")
                        .header("Authorization", "Bearer " + customer1Token))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/disputes/" + disputeId + "/status")
                        .header("Authorization", "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REFUNDED\",\"resolutionSummary\":\"Self-approve\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/delegate")
                        .header("Authorization", "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerDomain\":\"PARTNER_BANKSERV\",\"note\":\"Try delegate\"}"))
                .andExpect(status().isForbidden());

        // Affiliate cannot read or mutate a dispute outside its partner domain
        mockMvc.perform(get("/api/v1/disputes/" + disputeId)
                        .header("Authorization", "Bearer " + affiliateToken))
                .andExpect(status().isForbidden());

        // Staff claims and delegates to PARTNER_BANKSERV
        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/claim")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/delegate")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerDomain\":\"PARTNER_BANKSERV\",\"note\":\"Clearing trace required\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_EXTERNAL_REVIEW"))
                .andExpect(jsonPath("$.assignedDomain").value("PARTNER_BANKSERV"));

        // Affiliate can now read (with PII masked) and add a non-internal note
        mockMvc.perform(get("/api/v1/disputes/" + disputeId)
                        .header("Authorization", "Bearer " + affiliateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.piiMasked").value(true));

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/notes")
                        .header("Authorization", "Bearer " + affiliateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bankserv clearing trace initiated\",\"internalOnly\":false,\"requiresCustomerReply\":false}"))
                .andExpect(status().isOk());

        // Affiliate is still blocked from internal status updates, claim, escalate, delegate, and attachments
        mockMvc.perform(patch("/api/v1/disputes/" + disputeId + "/status")
                        .header("Authorization", "Bearer " + affiliateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REFUNDED\",\"resolutionSummary\":\"Affiliate attempt\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/status")
                        .header("Authorization", "Bearer " + affiliateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"resolutionSummary\":\"Affiliate POST attempt\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/claim")
                        .header("Authorization", "Bearer " + affiliateToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/escalate")
                        .header("Authorization", "Bearer " + affiliateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Affiliate escalate attempt\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/delegate")
                        .header("Authorization", "Bearer " + affiliateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerDomain\":\"PARTNER_MERCHANT\",\"note\":\"Re-delegate attempt\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(multipart("/api/v1/disputes/" + disputeId + "/attachments")
                        .file(extraFile)
                        .header("Authorization", "Bearer " + affiliateToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void enforcesStateMachineTransitionsAndAdminAssignmentValidation() throws Exception {
        String customer1Token = loginAndExtractToken("customer@capi.test", "Password123!");
        String staffToken = loginAndExtractToken("staff@capi.test", "Password123!");
        String adminToken = loginAndExtractToken("admin@capi.test", "Password123!");

        AppUser staffUser = appUserRepository.findByEmailIgnoreCase("staff@capi.test").orElseThrow();
        AppUser affiliateUser = appUserRepository.findByEmailIgnoreCase("affiliate@bankserv.test").orElseThrow();

        String disputeId = createDispute(customer1Token, "Checkers Sixty60", "499.00");

        // Direct LOGGED -> REFUNDED or LOGGED -> RESOLVED is not permitted by transition table
        mockMvc.perform(patch("/api/v1/disputes/" + disputeId + "/status")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REFUNDED\",\"resolutionSummary\":\"Skipping review\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/v1/disputes/" + disputeId + "/status")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"resolutionSummary\":\"Skipping review\"}"))
                .andExpect(status().isConflict());

        // Admin assignment rejects CUSTOMER domain and incompatible user/domain pairs
        mockMvc.perform(post("/api/v1/admin/disputes/" + disputeId + "/assign")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignedDomain\":\"CUSTOMER\",\"note\":\"Invalid domain\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/admin/disputes/" + disputeId + "/assign")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignedDomain\":\"INTERNAL_CAPI\",\"assigneeUserId\":\"" + affiliateUser.getId() + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/admin/disputes/" + disputeId + "/assign")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignedDomain\":\"PARTNER_BANKSERV\",\"assigneeUserId\":\"" + staffUser.getId() + "\"}"))
                .andExpect(status().isBadRequest());

        // Valid admin assignments succeed
        mockMvc.perform(post("/api/v1/admin/disputes/" + disputeId + "/assign")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignedDomain\":\"INTERNAL_CAPI\",\"assigneeUserId\":\"" + staffUser.getId() + "\",\"status\":\"IN_REVIEW\",\"note\":\"Assigned to investigator\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.assignedDomain").value("INTERNAL_CAPI"));

        mockMvc.perform(post("/api/v1/admin/disputes/" + disputeId + "/assign")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignedDomain\":\"PARTNER_BANKSERV\",\"assigneeUserId\":\"" + affiliateUser.getId() + "\",\"status\":\"PENDING_EXTERNAL_REVIEW\",\"note\":\"Handoff to Bankserv\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_EXTERNAL_REVIEW"))
                .andExpect(jsonPath("$.assignedDomain").value("PARTNER_BANKSERV"));

        // Staff resolves the dispute; terminal state blocks further transitions
        mockMvc.perform(patch("/api/v1/disputes/" + disputeId + "/status")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"resolutionSummary\":\"Merchant confirmed credit note\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        mockMvc.perform(patch("/api/v1/disputes/" + disputeId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_REVIEW\",\"resolutionSummary\":\"Attempt reopen via status\"}"))
                .andExpect(status().isConflict());
    }

    private String createDispute(String token, String merchantName, String amount) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "merchantName", merchantName,
                "paymentRail", "CARD_POS",
                "category", "UNAUTHORIZED_TRANSACTION",
                "amount", Double.parseDouble(amount),
                "reason", "Transaction not recognized by cardholder during reconciliation check."
        ));

        MvcResult result = mockMvc.perform(post("/api/v1/disputes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asText();
    }

    private String loginAndExtractToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }
}
