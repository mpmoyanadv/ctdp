package za.co.capi.dispute;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import za.co.capi.dispute.application.service.NotificationEmailService;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.FraudAlertToken;
import za.co.capi.dispute.domain.model.PasswordResetOtp;
import za.co.capi.dispute.domain.repository.AppUserRepository;
import za.co.capi.dispute.domain.repository.FraudAlertTokenRepository;
import za.co.capi.dispute.domain.repository.PasswordResetOtpRepository;
import za.co.capi.dispute.infrastructure.cache.OtpRateLimiterService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtpSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private FraudAlertTokenRepository fraudAlertTokenRepository;

    @Autowired
    private PasswordResetOtpRepository passwordResetOtpRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OtpRateLimiterService otpRateLimiterService;

    @MockBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setUp() {
        reset(javaMailSender);
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        fraudAlertTokenRepository.deleteAll();
        passwordResetOtpRepository.deleteAll();
        otpRateLimiterService.clearLocalLimits();

        AppUser customer = appUserRepository.findByEmailIgnoreCase("customer@capi.test").orElseThrow();
        customer.setLocked(false);
        customer.setLockedReason(null);
        customer.setFullName("Thabo Mokoena");
        customer.setPasswordHash(passwordEncoder.encode("Password123!"));
        appUserRepository.save(customer);
    }

    @Test
    void rejectsExpiredAndReusedOtpsAndStoresOnlyHashedOtp() throws Exception {
        String otpCode = triggerFraudLockAndCaptureOtp("customer@capi.test");

        PasswordResetOtp savedOtp = passwordResetOtpRepository.findAll().getFirst();
        assertThat(savedOtp.getOtpHash()).isNotEqualTo(otpCode).startsWith("$2");

        // Expired OTP -> 400
        savedOtp.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        passwordResetOtpRepository.save(savedOtp);

        mockMvc.perform(post("/api/v1/auth/reset-password-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"customer@capi.test","otpCode":"%s","newPassword":"NewPassword123!"}
                                """.formatted(otpCode)))
                .andExpect(status().isBadRequest());

        // Restore valid expiry -> first use succeeds
        savedOtp.setExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        passwordResetOtpRepository.save(savedOtp);

        mockMvc.perform(post("/api/v1/auth/reset-password-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"customer@capi.test","otpCode":"%s","newPassword":"NewPassword123!"}
                                """.formatted(otpCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isLocked").value(false));

        // Reuse same OTP -> 400
        mockMvc.perform(post("/api/v1/auth/reset-password-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"customer@capi.test","otpCode":"%s","newPassword":"AnotherPassword123!"}
                                """.formatted(otpCode)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidatesOlderOtpWhenNewOtpIsIssued() throws Exception {
        String firstOtp = triggerFraudLockAndCaptureOtp("customer@capi.test");

        // Unlock via admin or DB so customer can log in again, then issue a second OTP
        AppUser customer = appUserRepository.findByEmailIgnoreCase("customer@capi.test").orElseThrow();
        customer.setLocked(false);
        appUserRepository.save(customer);

        String secondOtp = triggerFraudLockAndCaptureOtp("customer@capi.test");
        assertThat(secondOtp).isNotEqualTo(firstOtp);

        // Old OTP must now be invalidated even if it was never used
        mockMvc.perform(post("/api/v1/auth/reset-password-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"customer@capi.test","otpCode":"%s","newPassword":"NewPassword123!"}
                                """.formatted(firstOtp)))
                .andExpect(status().isBadRequest());

        // New OTP still works
        mockMvc.perform(post("/api/v1/auth/reset-password-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"customer@capi.test","otpCode":"%s","newPassword":"NewPassword123!"}
                                """.formatted(secondOtp)))
                .andExpect(status().isOk());
    }

    @Test
    void burnsOtpAfterMaxFailedAttemptsAndEnforcesRateLimit() throws Exception {
        String validOtp = triggerFraudLockAndCaptureOtp("customer@capi.test");
        String wrongOtp = validOtp.equals("000000") ? "999999" : "000000";

        // 4 failed attempts -> 400
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/reset-password-verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"customer@capi.test","otpCode":"%s","newPassword":"NewPassword123!"}
                                    """.formatted(wrongOtp)))
                    .andExpect(status().isBadRequest());
        }

        // 5th failed attempt hits MAX_OTP_FAILED_ATTEMPTS -> 429 and burns OTP
        mockMvc.perform(post("/api/v1/auth/reset-password-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"customer@capi.test","otpCode":"%s","newPassword":"NewPassword123!"}
                                """.formatted(wrongOtp)))
                .andExpect(status().isTooManyRequests());

        // 6th request is rate-limited (429) even with the originally valid OTP
        mockMvc.perform(post("/api/v1/auth/reset-password-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"customer@capi.test","otpCode":"%s","newPassword":"NewPassword123!"}
                                """.formatted(validOtp)))
                .andExpect(status().isTooManyRequests());

        // Even after clearing rate limit, the burned OTP cannot be used
        otpRateLimiterService.clearLocalLimits();
        mockMvc.perform(post("/api/v1/auth/reset-password-verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"customer@capi.test","otpCode":"%s","newPassword":"NewPassword123!"}
                                """.formatted(validOtp)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRequestsCannotBothConsumeSameOtp() throws Exception {
        String validOtp = triggerFraudLockAndCaptureOtp("customer@capi.test");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> task = () -> {
                ready.countDown();
                start.await();
                MvcResult result = mockMvc.perform(post("/api/v1/auth/reset-password-verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email":"customer@capi.test","otpCode":"%s","newPassword":"ConcurrentPass123!"}
                                        """.formatted(validOtp)))
                        .andReturn();
                return result.getResponse().getStatus();
            };

            Future<Integer> f1 = pool.submit(task);
            Future<Integer> f2 = pool.submit(task);
            ready.await();
            start.countDown();

            List<Integer> statuses = List.of(f1.get(), f2.get());
            assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s != 200).count()).isEqualTo(1);
        }
    }

    @Test
    void otpIsNeverPresentInEmailSubjectOrApplicationLogsAndHtmlIsEscaped(CapturedOutput output) throws Exception {
        AppUser customer = appUserRepository.findByEmailIgnoreCase("customer@capi.test").orElseThrow();
        customer.setFullName("Thabo <script>alert(1)</script> Mokoena");
        appUserRepository.save(customer);

        String otpCode = triggerFraudLockAndCaptureOtp("customer@capi.test");

        ArgumentCaptor<MimeMessage> mailCaptor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender, atLeastOnce()).send(mailCaptor.capture());
        MimeMessage otpEmail = mailCaptor.getAllValues().getLast();
        String body = otpEmail.getContent().toString();

        assertThat(otpEmail.getSubject()).isEqualTo(NotificationEmailService.ACCOUNT_LOCKED_OTP_SUBJECT);
        assertThat(otpEmail.getSubject()).doesNotContain(otpCode);
        assertThat(body).contains(otpCode);
        assertThat(body).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(body).doesNotContain("<script>alert(1)</script>");

        String logs = output.getAll();
        assertThat(logs).contains("ACCOUNT_LOCKED_OTP");
        assertThat(logs).contains("cu****@capi.test");
        assertThat(logs).doesNotContain(otpCode);
        assertThat(logs).doesNotContain("customer@capi.test");
    }

    private String triggerFraudLockAndCaptureOtp(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk());

        FraudAlertToken latestToken = fraudAlertTokenRepository.findAll().stream()
                .filter(t -> !t.isUsed())
                .reduce((first, second) -> second)
                .orElseThrow();

        mockMvc.perform(post("/api/v1/auth/report-fraud")
                        .param("token", latestToken.getToken()))
                .andExpect(status().isOk());

        ArgumentCaptor<MimeMessage> mailCaptor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender, atLeastOnce()).send(mailCaptor.capture());
        List<MimeMessage> sent = mailCaptor.getAllValues();
        Pattern pattern = Pattern.compile("id=\"otp-code\"[^>]*>(\\d{6})</span>");
        for (int i = sent.size() - 1; i >= 0; i--) {
            Object content = sent.get(i).getContent();
            String body = content != null ? content.toString() : "";
            Matcher matcher = pattern.matcher(body);
            if (matcher.find()) {
                String otpCode = matcher.group(1);
                assertThat(sent.get(i).getSubject()).doesNotContain(otpCode);
                return otpCode;
            }
        }
        throw new IllegalStateException("No OTP found in email bodies");
    }
}

