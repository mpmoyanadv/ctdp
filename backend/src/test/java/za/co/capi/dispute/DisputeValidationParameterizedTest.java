package za.co.capi.dispute;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.stream.Stream;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
class DisputeValidationParameterizedTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JavaMailSender javaMailSender;

    private String customerToken;

    @BeforeEach
    void setUp() throws Exception {
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@capi.test\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        customerToken = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asText();
    }

    static Stream<Arguments> invalidDisputePayloads() {
        return Stream.of(
                Arguments.of("-1.00", "Valid dispute explanation"),
                Arguments.of("0.00", "Valid dispute explanation"),
                Arguments.of("10000000.00", "Valid dispute explanation"),
                Arguments.of("100.999", "Valid dispute explanation"),
                Arguments.of("100.00", " x ")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidDisputePayloads")
    void rejectsInvalidAmountOrTrimmedReasonOnJsonEndpoint(String amount, String reason) throws Exception {
        String body = """
                {
                  "merchantName": "Validation Merchant",
                  "paymentRail": "CARD_POS",
                  "category": "UNAUTHORIZED_TRANSACTION",
                  "amount": %s,
                  "reason": %s
                }
                """.formatted(amount, objectMapper.writeValueAsString(reason));

        mockMvc.perform(post("/api/v1/disputes")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @MethodSource("invalidDisputePayloads")
    void rejectsInvalidAmountOrTrimmedReasonOnMultipartEndpoint(String amount, String reason) throws Exception {
        mockMvc.perform(multipart("/api/v1/disputes")
                        .header("Authorization", "Bearer " + customerToken)
                        .param("merchantName", "Validation Merchant")
                        .param("paymentRail", "CARD_POS")
                        .param("category", "UNAUTHORIZED_TRANSACTION")
                        .param("amount", amount)
                        .param("reason", reason))
                .andExpect(status().isBadRequest());
    }
}
