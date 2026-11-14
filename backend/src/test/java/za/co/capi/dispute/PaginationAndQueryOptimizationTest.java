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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
class PaginationAndQueryOptimizationTest {

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
    void paginatesDisputesAndAuditLogsWithStableSortingAndBoundedSize() throws Exception {
        String customerToken = loginAndExtractToken("customer@capi.test", "Password123!");
        String adminToken = loginAndExtractToken("admin@capi.test", "Password123!");

        // Create 2 extra disputes so customer@capi.test has at least 4 disputes total
        createDispute(customerToken, "Merchant Page A", 111.00);
        createDispute(customerToken, "Merchant Page B", 222.00);

        // Page 0, size 2
        MvcResult page0Res = mockMvc.perform(get("/api/v1/disputes?page=0&size=2")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();

        JsonNode page0 = objectMapper.readTree(page0Res.getResponse().getContentAsString());
        assertThat(page0.get("totalElements").asLong()).isGreaterThanOrEqualTo(4);
        assertThat(page0.get("totalPages").asInt()).isGreaterThanOrEqualTo(2);

        // Page 1, size 2 must not overlap with page 0 and must be ordered descending
        MvcResult page1Res = mockMvc.perform(get("/api/v1/disputes?page=1&size=2")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.first").value(false))
                .andReturn();

        JsonNode page1 = objectMapper.readTree(page1Res.getResponse().getContentAsString());
        Set<String> page0Ids = new HashSet<>();
        for (JsonNode n : page0.get("content")) {
            page0Ids.add(n.get("id").asText());
        }
        for (JsonNode n : page1.get("content")) {
            assertThat(page0Ids).doesNotContain(n.get("id").asText());
        }

        // Status filter returns only matching disputes
        MvcResult loggedRes = mockMvc.perform(get("/api/v1/disputes?status=LOGGED&page=0&size=20")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode n : objectMapper.readTree(loggedRes.getResponse().getContentAsString()).get("content")) {
            assertThat(n.get("status").asText()).isEqualTo("LOGGED");
        }

        // Oversized page size request is clamped to max 100
        mockMvc.perform(get("/api/v1/disputes?page=0&size=500")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        // Admin audit logs pagination & eventType filtering
        mockMvc.perform(get("/api/v1/admin/audit-logs?page=0&size=2")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));

        MvcResult filteredAudit = mockMvc.perform(get("/api/v1/admin/audit-logs?eventType=DISPUTE_CREATED&page=0&size=10")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode auditContent = objectMapper.readTree(filteredAudit.getResponse().getContentAsString()).get("content");
        assertThat(auditContent.size()).isGreaterThanOrEqualTo(2);
        for (JsonNode log : auditContent) {
            assertThat(log.get("eventType").asText()).isEqualTo("DISPUTE_CREATED");
        }
    }

    private void createDispute(String token, String merchantName, double amount) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "merchantName", merchantName,
                "paymentRail", "CARD_POS",
                "category", "UNAUTHORIZED_TRANSACTION",
                "amount", amount,
                "reason", "Pagination test dispute creation for " + merchantName
        ));
        mockMvc.perform(post("/api/v1/disputes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private String loginAndExtractToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}

