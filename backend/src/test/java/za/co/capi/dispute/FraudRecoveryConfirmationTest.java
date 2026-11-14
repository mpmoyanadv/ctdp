package za.co.capi.dispute;

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
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.FraudAlertToken;
import za.co.capi.dispute.domain.repository.AppUserRepository;
import za.co.capi.dispute.domain.repository.FraudAlertTokenRepository;
import za.co.capi.dispute.domain.repository.PasswordResetOtpRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FraudRecoveryConfirmationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private FraudAlertTokenRepository fraudAlertTokenRepository;

    @Autowired
    private PasswordResetOtpRepository passwordResetOtpRepository;

    @MockBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setUp() {
        when(javaMailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    @Test
    void getRequestsDoNotLockAccountWhileExplicitPostDoes() throws Exception {
        fraudAlertTokenRepository.deleteAll();
        passwordResetOtpRepository.deleteAll();

        // Trigger customer login to generate a fresh FraudAlertToken
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@capi.test\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk());

        FraudAlertToken alertToken = fraudAlertTokenRepository.findAll().getFirst();
        AppUser beforeGet = appUserRepository.findByEmailIgnoreCase("customer@capi.test").orElseThrow();
        int initialTokenVersion = beforeGet.getTokenVersion();

        // 1. Email-scanner GET redirect must NOT lock the account or consume the token
        mockMvc.perform(get("/api/v1/auth/report-fraud")
                        .param("token", alertToken.getToken()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/fraud-report?token=")))
                .andExpect(header().string("Location", not(containsString("locked=true"))));

        // 2. Preview GET must also leave account unlocked and token unused
        mockMvc.perform(get("/api/v1/auth/report-fraud/preview")
                        .param("token", alertToken.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.maskedEmail").value("cu****@capi.test"));

        AppUser afterGet = appUserRepository.findByEmailIgnoreCase("customer@capi.test").orElseThrow();
        FraudAlertToken tokenAfterGet = fraudAlertTokenRepository.findById(alertToken.getId()).orElseThrow();
        assertThat(afterGet.isLocked()).isFalse();
        assertThat(afterGet.getTokenVersion()).isEqualTo(initialTokenVersion);
        assertThat(tokenAfterGet.isUsed()).isFalse();
        assertThat(passwordResetOtpRepository.count()).isZero();

        // 3. Explicit POST confirmation locks the account, consumes the token, and issues OTP
        mockMvc.perform(post("/api/v1/auth/report-fraud")
                        .param("token", alertToken.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true));

        AppUser afterPost = appUserRepository.findByEmailIgnoreCase("customer@capi.test").orElseThrow();
        FraudAlertToken tokenAfterPost = fraudAlertTokenRepository.findById(alertToken.getId()).orElseThrow();
        assertThat(afterPost.isLocked()).isTrue();
        assertThat(afterPost.getTokenVersion()).isEqualTo(initialTokenVersion + 1);
        assertThat(tokenAfterPost.isUsed()).isTrue();
        assertThat(passwordResetOtpRepository.count()).isEqualTo(1);
    }
}
