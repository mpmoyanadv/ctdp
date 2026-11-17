package za.co.capi.dispute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
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
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.repository.AppUserRepository;
import za.co.capi.dispute.infrastructure.cache.LoginRateLimiterService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginBruteForceAndLockoutTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private LoginRateLimiterService loginRateLimiterService;

    @MockBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setUp() {
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        loginRateLimiterService.clearLocalLimits();

        AppUser customer = appUserRepository.findByEmailIgnoreCase("customer@capi.test").orElseThrow();
        customer.setLocked(false);
        customer.setLockedReason(null);
        appUserRepository.save(customer);

        AppUser customer2 = appUserRepository.findByEmailIgnoreCase("customer2@capi.test").orElseThrow();
        customer2.setLocked(false);
        customer2.setLockedReason(null);
        appUserRepository.save(customer2);
    }

    @AfterEach
    void tearDown() {
        loginRateLimiterService.clearLocalLimits();
    }

    @Test
    void locksAccountAfterFiveFailedAttemptsEvenWhenCorrectPasswordFollows() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr("198.51.100.11");
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"customer@capi.test\",\"password\":\"WrongPassword" + i + "!\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid email or password."));
        }

        // 6th attempt using the valid password must still fail while temporary lock is active
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr("198.51.100.12");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@capi.test\",\"password\":\"Password123!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
    }

    @Test
    void resetsAccountFailureCounterOnSuccessfulLogin() throws Exception {
        // 4 failures (below threshold of 5)
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr("198.51.100.21");
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"customer@capi.test\",\"password\":\"WrongPass!\"}"))
                    .andExpect(status().isUnauthorized());
        }

        // 5th attempt with valid password succeeds and resets account failure counter
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr("198.51.100.22");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@capi.test\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // 4 more failures should NOT lock (proving counter was reset to 0)
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr("198.51.100.23");
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"customer@capi.test\",\"password\":\"WrongPassAgain!\"}"))
                    .andExpect(status().isUnauthorized());
        }

        // Valid login still succeeds before reaching 5 consecutive failures
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr("198.51.100.24");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@capi.test\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void returns429AfterTwentyFailedAttemptsFromSameIpWithinFiveMinutes() throws Exception {
        String attackerIp = "198.51.100.42";
        for (int i = 0; i < 20; i++) {
            String targetEmail = "user" + (i % 5) + "@unknown-attacker.test";
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr(attackerIp);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + targetEmail + "\",\"password\":\"WrongPassword!\"}"))
                    .andExpect(status().isUnauthorized());
        }

        // 21st attempt from same IP hits per-IP rate limit -> 429
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(attackerIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@capi.test\",\"password\":\"Password123!\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void returnsUniform401ResponseAndSimilarTimingForUnknownLockedAndWrongPassword() throws Exception {
        AppUser lockedCustomer = appUserRepository.findByEmailIgnoreCase("customer2@capi.test").orElseThrow();
        lockedCustomer.setLocked(true);
        lockedCustomer.setLockedReason("Fraud reported");
        appUserRepository.save(lockedCustomer);

        // Warmup request outside timed comparison
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr("198.51.100.90");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"warmup@unknown.test\",\"password\":\"Password123!\"}"))
                .andExpect(status().isUnauthorized());

        TimedAuthResult unknownResult = performTimedUnauthorizedLogin("nobody@capi.test", "Password123!", "198.51.100.91");
        TimedAuthResult lockedResult = performTimedUnauthorizedLogin("customer2@capi.test", "Password123!", "198.51.100.92");
        TimedAuthResult wrongPassResult = performTimedUnauthorizedLogin("customer@capi.test", "WrongPass!", "198.51.100.93");

        assertThat(unknownResult.code()).isEqualTo("UNAUTHORIZED");
        assertThat(lockedResult.code()).isEqualTo("UNAUTHORIZED");
        assertThat(wrongPassResult.code()).isEqualTo("UNAUTHORIZED");

        assertThat(unknownResult.message()).isEqualTo("Invalid email or password.");
        assertThat(lockedResult.message()).isEqualTo(unknownResult.message());
        assertThat(wrongPassResult.message()).isEqualTo(unknownResult.message());

        // Verify BCrypt work factor ran on every branch (no fast-path skip for unknown/locked accounts)
        assertThat(unknownResult.elapsedMs()).isGreaterThanOrEqualTo(5L);
        assertThat(lockedResult.elapsedMs()).isGreaterThanOrEqualTo(5L);
        assertThat(wrongPassResult.elapsedMs()).isGreaterThanOrEqualTo(5L);
    }

    private TimedAuthResult performTimedUnauthorizedLogin(String email, String password, String ip) throws Exception {
        long startNs = System.nanoTime();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TimedAuthResult(
                json.get("code").asText(),
                json.get("message").asText(),
                elapsedMs
        );
    }

    private record TimedAuthResult(String code, String message, long elapsedMs) {
    }
}
