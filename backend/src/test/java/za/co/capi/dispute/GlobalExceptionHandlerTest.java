package za.co.capi.dispute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import za.co.capi.dispute.presentation.exception.GlobalExceptionHandler;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GlobalExceptionHandler exceptionHandler;

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

    @Test
    void returnsSanitized400ForMalformedUuidInvalidEnumAndMalformedJson() throws Exception {
        MvcResult badUuid = mockMvc.perform(get("/api/v1/disputes/not-a-valid-uuid")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn();
        assertSanitized400Body(badUuid.getResponse().getContentAsString());

        MvcResult badEnumParam = mockMvc.perform(get("/api/v1/disputes?status=NOT_A_REAL_STATUS")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn();
        assertSanitized400Body(badEnumParam.getResponse().getContentAsString());

        MvcResult badJsonEnum = mockMvc.perform(post("/api/v1/disputes")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"INVALID_CATEGORY\",\"amount\":100.00,\"reason\":\"Valid dispute reason\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertSanitized400Body(badJsonEnum.getResponse().getContentAsString());

        MvcResult malformedJson = mockMvc.perform(post("/api/v1/disputes")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{malformed-json"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertSanitized400Body(malformedJson.getResponse().getContentAsString());
    }

    @Test
    void returnsGeneric500WithTraceIdAndLogsFullStackTrace(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/disputes/boom");
        request.setAttribute("traceId", "trace-test-500-xyz");

        ResponseEntity<Map<String, Object>> response = exceptionHandler.handleUnexpected(
                new RuntimeException("Sensitive SQL detail: relation foo does not exist"),
                request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("traceId")).isEqualTo("trace-test-500-xyz");
        assertThat(body.get("code")).isEqualTo("INTERNAL_ERROR");
        String message = String.valueOf(body.get("message"));
        assertThat(message).contains("trace-test-500-xyz");
        assertThat(message).doesNotContain("Sensitive SQL detail");
        assertThat(message).doesNotContain("RuntimeException");

        String logs = output.getAll();
        assertThat(logs).contains("trace-test-500-xyz");
        assertThat(logs).contains("java.lang.RuntimeException: Sensitive SQL detail: relation foo does not exist");
    }

    private void assertSanitized400Body(String body) throws Exception {
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("status").asInt()).isEqualTo(400);
        assertThat(json.get("traceId").asText()).isNotBlank();
        String message = json.get("message").asText();
        assertThat(message).doesNotContain("java.");
        assertThat(message).doesNotContain("org.springframework");
        assertThat(message).doesNotContain("com.fasterxml");
        assertThat(message).doesNotContain("za.co.capi");
        assertThat(message).doesNotContain("Exception");
    }
}
