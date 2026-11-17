package za.co.capi.dispute;

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
import za.co.capi.dispute.domain.repository.StaffWorkSessionRepository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DisputeConcurrentClaimTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StaffWorkSessionRepository workSessionRepository;

    @MockBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setupMailMock() {
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    @Test
    void concurrentClaimsAllowOnlyOne200AndOne409WithSingleWorkSession() throws Exception {
        String customerToken = login("customer@capi.test");
        String staffToken = login("staff@capi.test");
        String adminToken = login("admin@capi.test");

        MvcResult created = mockMvc.perform(post("/api/v1/disputes")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantName": "Concurrent Claim Merchant",
                                  "paymentRail": "CARD_POS",
                                  "category": "UNAUTHORIZED_TRANSACTION",
                                  "amount": 325.00,
                                  "reason": "Disputing simultaneous claim locking behavior."
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID disputeId = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText()
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> claimAsStaff = () -> claim(disputeId, staffToken, ready, start);
            Callable<Integer> claimAsAdmin = () -> claim(disputeId, adminToken, ready, start);

            Future<Integer> f1 = pool.submit(claimAsStaff);
            Future<Integer> f2 = pool.submit(claimAsAdmin);
            ready.await();
            start.countDown();

            List<Integer> statuses = List.of(f1.get(), f2.get());
            assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(1);
        }

        assertThat(workSessionRepository.findByDisputeIdAndCompletedAtIsNull(disputeId)).hasSize(1);
    }

    private int claim(UUID disputeId, String token, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/claim")
                        .header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse()
                .getStatus();
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
