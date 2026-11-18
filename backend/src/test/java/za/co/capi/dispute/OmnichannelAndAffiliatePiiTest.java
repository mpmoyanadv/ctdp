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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OmnichannelAndAffiliatePiiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setupMailMock() {
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    @Test
    void affiliateViewingVoiceCallDisputeSeesNoUnmaskedPhoneSaIdOrAccountNumber() throws Exception {
        String staffToken = login("staff@capi.test");
        String affiliateToken = login("affiliate@bankserv.test");

        MvcResult voiceResult = mockMvc.perform(post("/api/v1/omnichannel/voice-call")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "callerPhoneNumber": "+27825550192",
                                  "transactionReference": "TXN-2026-9004",
                                  "scenario": "CARD_COMPROMISE_TEST",
                                  "category": "UNAUTHORIZED_TRANSACTION",
                                  "callerStatement": "Caller Thabo Mokoena on +27825550192 ID 8501015800082 account 1548920341 reported TXN-2026-9004.",
                                  "agentNotes": "Customer confirmed +27825550192",
                                  "cardFreezeRequested": true,
                                  "idempotencyKey": "voice-pii-scrub-001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode voiceJson = objectMapper.readTree(voiceResult.getResponse().getContentAsString());
        String disputeId = voiceJson.get("dispute").get("id").asText();
        String metadata = voiceJson.get("dispute").get("contactChannelMetadata").asText();
        assertThat(metadata).doesNotContain("+27825550192");

        // Delegate to PARTNER_BANKSERV with a public note that also contains raw identifiers
        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/delegate")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "partnerDomain": "PARTNER_BANKSERV",
                                  "note": "Check rail trace for phone +27825550192 SA ID 8501015800082 acct 1548920341 email customer@capi.test"
                                }
                                """))
                .andExpect(status().isOk());

        MvcResult affiliateDetail = mockMvc.perform(get("/api/v1/disputes/" + disputeId)
                        .header("Authorization", "Bearer " + affiliateToken))
                .andExpect(status().isOk())
                .andReturn();

        String detailBody = affiliateDetail.getResponse().getContentAsString();
        assertThat(detailBody)
                .doesNotContain("+27825550192")
                .doesNotContain("8501015800082")
                .doesNotContain("1548920341")
                .doesNotContain("customer@capi.test")
                .doesNotContain("Thabo Mokoena")
                .doesNotContain("VOICE CALL RECORDING");

        MvcResult affiliateList = mockMvc.perform(get("/api/v1/disputes")
                        .header("Authorization", "Bearer " + affiliateToken))
                .andExpect(status().isOk())
                .andReturn();

        String listBody = affiliateList.getResponse().getContentAsString();
        assertThat(listBody)
                .doesNotContain("+27825550192")
                .doesNotContain("8501015800082")
                .doesNotContain("1548920341")
                .doesNotContain("customer@capi.test")
                .doesNotContain("Thabo Mokoena");
    }

    @Test
    void omnichannelRejectsMissingPhoneOrTransactionReferenceWith400() throws Exception {
        String staffToken = login("staff@capi.test");

        // Missing phone on SMS webhook -> 400
        mockMvc.perform(post("/api/v1/omnichannel/sms-webhook")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "channel": "SMS",
                                  "messageBody": "DISPUTE TXN-2026-9005 unauthorized charge"
                                }
                                """))
                .andExpect(status().isBadRequest());

        // Missing transaction reference and transactionId on SMS webhook -> 400
        mockMvc.perform(post("/api/v1/omnichannel/sms-webhook")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "channel": "SMS",
                                  "fromPhoneNumber": "+27825550192",
                                  "messageBody": "Unknown charge occurred earlier today"
                                }
                                """))
                .andExpect(status().isBadRequest());

        // Missing phone on Voice Call -> 400
        mockMvc.perform(post("/api/v1/omnichannel/voice-call")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transactionReference": "TXN-2026-9001",
                                  "callerStatement": "Customer reported double tap at fuel pump."
                                }
                                """))
                .andExpect(status().isBadRequest());

        // Missing transaction reference and transactionId on Voice Call -> 400
        mockMvc.perform(post("/api/v1/omnichannel/voice-call")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "callerPhoneNumber": "+27825550192",
                                  "callerStatement": "Customer reported unauthorized card use without ref."
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifiesCustomerViaSaIdAndTwoSecurityOrCreditBureauQuestionsAndReturnsCustomerTransactions() throws Exception {
        String customerToken = login("customer@capi.test");
        String staffToken = login("staff@capi.test");

        String validBody = """
                {
                  "saIdNumber": "8501015800082",
                  "phoneNumber": "+27825550192",
                  "hasCreditCardFacility": false,
                  "hasActiveDebitOrderMandate": true
                }
                """;

        // Customer cannot access caller lookup, not-verified, or verification endpoints -> 403
        mockMvc.perform(get("/api/v1/omnichannel/lookup-customer")
                        .param("saIdNumber", "8501015800082")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/omnichannel/not-verified")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saIdNumber\":\"8501015800082\",\"reason\":\"Attempt\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/omnichannel/verify-customer")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isForbidden());

        // Staff loads customer details by SA ID to verify whilst on the call -> 200
        mockMvc.perform(get("/api/v1/omnichannel/lookup-customer")
                        .param("saIdNumber", "8501015800082")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Thabo Mokoena"))
                .andExpect(jsonPath("$.phoneNumber").value("+27825550192"))
                .andExpect(jsonPath("$.accountNumber").value("1548920341"))
                .andExpect(jsonPath("$.hasCreditCardFacility").value(false))
                .andExpect(jsonPath("$.hasActiveDebitOrderMandate").value(true));

        // Unknown SA ID lookup -> 404 (and persists CALLER_KYC_LOOKUP_FAILED audit event)
        mockMvc.perform(get("/api/v1/omnichannel/lookup-customer")
                        .param("saIdNumber", "0000000000000")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isNotFound());

        // Staff marks caller as Unverified -> 200 & verified=false
        mockMvc.perform(post("/api/v1/omnichannel/not-verified")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saIdNumber\":\"8501015800082\",\"reason\":\"Caller failed security check\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false));

        // Fewer than 2 optional security / credit-bureau questions answered -> 400
        mockMvc.perform(post("/api/v1/omnichannel/verify-customer")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "saIdNumber": "8501015800082",
                                  "phoneNumber": "+27825550192"
                                }
                                """))
                .andExpect(status().isBadRequest());

        // Incorrect credit-bureau answer -> 403
        mockMvc.perform(post("/api/v1/omnichannel/verify-customer")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "saIdNumber": "8501015800082",
                                  "phoneNumber": "+27825550192",
                                  "hasCreditCardFacility": true
                                }
                                """))
                .andExpect(status().isForbidden());

        // Verified staff KYC request -> 200 with caller's 5 transactions
        mockMvc.perform(post("/api/v1/omnichannel/verify-customer")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.questionsAnswered").value(3))
                .andExpect(jsonPath("$.customer.fullName").value("Thabo Mokoena"))
                .andExpect(jsonPath("$.transactions.length()").value(5));

        // Staff loads & verifies foreign customer by Foreign Passport (case-insensitive) -> 200 with 3 transactions
        mockMvc.perform(get("/api/v1/omnichannel/lookup-customer")
                        .param("saIdNumber", "zw4829104")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Tendai Moyo"))
                .andExpect(jsonPath("$.saIdNumber").value("ZW4829104"))
                .andExpect(jsonPath("$.phoneNumber").value("+27718842109"))
                .andExpect(jsonPath("$.accountNumber").value("1740882190"))
                .andExpect(jsonPath("$.hasCreditCardFacility").value(false))
                .andExpect(jsonPath("$.hasActiveDebitOrderMandate").value(false));

        mockMvc.perform(post("/api/v1/omnichannel/verify-customer")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "saIdNumber": "zw4829104",
                                  "phoneNumber": "+27718842109",
                                  "hasCreditCardFacility": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.customer.fullName").value("Tendai Moyo"))
                .andExpect(jsonPath("$.transactions.length()").value(3));

        // Call Centre captures dispute details with Transaction Type (EFT) & optional Location
        mockMvc.perform(post("/api/v1/omnichannel/voice-call")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "callerPhoneNumber": "+27825550192",
                                  "merchantName": "Vodacom EFT Payment",
                                  "paymentRail": "EFT",
                                  "location": "Sandton City Branch",
                                  "amount": 650.00,
                                  "scenario": "CALL_CENTRE_ASSISTED",
                                  "category": "UNAUTHORIZED_TRANSACTION",
                                  "callerStatement": "Verified caller requested EFT dispute capture for R650.",
                                  "cardFreezeRequested": false,
                                  "idempotencyKey": "voice-eft-custom-001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dispute.paymentRail").value("EFT"))
                .andExpect(jsonPath("$.dispute.merchantName").value("Vodacom EFT Payment"))
                .andExpect(jsonPath("$.channelTranscript").value(org.hamcrest.Matchers.containsString("Location: Sandton City Branch")));

        // Admin investigates immutable audit trail -> all Call Centre KYC lookup, verified, and unverified events persisted
        String adminToken = login("admin@capi.test");
        MvcResult auditResult = mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .param("size", "100")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String auditBody = auditResult.getResponse().getContentAsString();
        assertThat(auditBody)
                .contains("CALLER_KYC_LOOKUP")
                .contains("CALLER_KYC_LOOKUP_FAILED")
                .contains("CALLER_KYC_VERIFIED")
                .contains("CALLER_KYC_VERIFICATION_FAILED")
                .contains("8501015800082")
                .contains("0000000000000")
                .contains("staff@capi.test")
                .contains("UNVERIFIED_MISMATCH")
                .contains("INSUFFICIENT_CHECKS");
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
