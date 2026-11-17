package za.co.capi.dispute;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import za.co.capi.dispute.domain.model.AuditLog;
import za.co.capi.dispute.domain.repository.AuditLogRepository;
import za.co.capi.dispute.infrastructure.cache.LoginRateLimiterService;
import za.co.capi.dispute.infrastructure.security.ClientIpResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
class CorsAndProxyTrustTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClientIpResolver clientIpResolver;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private LoginRateLimiterService loginRateLimiterService;

    @MockBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setUp() {
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        loginRateLimiterService.clearLocalLimits();
    }

    @AfterEach
    void tearDown() {
        loginRateLimiterService.clearLocalLimits();
    }

    @Test
    void allowsConfiguredFrontendOriginOnCorsPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
    }

    @Test
    void rejectsUnapprovedOriginOnCorsPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void ignoresForwardedHeadersWhenRemoteAddrIsNotATrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.42");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.99");
        request.addHeader("X-Real-IP", "203.0.113.88");

        assertThat(new ClientIpResolver(List.of("10.0.0.0/8")).resolveClientIp(request)).isEqualTo("198.51.100.42");
        assertThat(new ClientIpResolver(List.of()).resolveClientIp(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void takesRightMostUntrustedAddressFromXForwardedForAndRecordsItInAuditLog() throws Exception {
        MockHttpServletRequest unitRequest = new MockHttpServletRequest();
        unitRequest.setRemoteAddr("10.24.1.5");
        unitRequest.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.50");
        assertThat(clientIpResolver.resolveClientIp(unitRequest)).isEqualTo("203.0.113.50");

        MockHttpServletRequest multiHopRequest = new MockHttpServletRequest();
        multiHopRequest.setRemoteAddr("10.24.1.5");
        multiHopRequest.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.50, 10.24.1.9");
        assertThat(clientIpResolver.resolveClientIp(multiHopRequest)).isEqualTo("203.0.113.50");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr("10.24.1.5");
                            return req;
                        })
                        .header("X-Forwarded-For", "6.6.6.6, 203.0.113.50")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"staff@capi.test\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk());

        AuditLog latestAudit = auditLogRepository.findAll(
                PageRequest.of(0, 1, Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id")))
        ).getContent().getFirst();

        assertThat(latestAudit.getEventType()).isEqualTo("LOGIN_SUCCESS");
        assertThat(latestAudit.getIpAddress()).isEqualTo("203.0.113.50");
    }
}
