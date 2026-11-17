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

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
