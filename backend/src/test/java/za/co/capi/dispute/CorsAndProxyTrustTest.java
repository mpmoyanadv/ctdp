package za.co.capi.dispute;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import za.co.capi.dispute.application.service.AuditService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsAndProxyTrustTest {

    @Autowired
    private MockMvc mockMvc;

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
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        request.addHeader("X-Real-IP", "203.0.113.88");

        assertThat(AuditService.extractClientIp(request, List.of("10.0.0.0/8"))).isEqualTo("198.51.100.42");
        assertThat(AuditService.extractClientIp(request, List.of())).isEqualTo("198.51.100.42");
    }

    @Test
    void trustsForwardedHeadersOnlyFromConfiguredProxyCidr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.24.1.5");
        request.addHeader("X-Forwarded-For", "203.0.113.99, 10.24.1.5");

        assertThat(AuditService.extractClientIp(request, List.of("10.0.0.0/8"))).isEqualTo("203.0.113.99");
        // Also verify the Spring-injected application-test.yml config (10.0.0.0/8)
        assertThat(AuditService.extractClientIp(request)).isEqualTo("203.0.113.99");
    }
}

