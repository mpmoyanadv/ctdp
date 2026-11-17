package za.co.capi.dispute.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Component
public class ClientIpResolver {

    private final List<IpAddressMatcher> trustedProxyMatchers;

    @Autowired
    public ClientIpResolver(@Value("${app.security.trusted-proxies:}") List<String> trustedProxies) {
        this.trustedProxyMatchers = compileProxyMatchers(trustedProxies);
    }

    public ClientIpResolver(Collection<String> trustedProxies) {
        this.trustedProxyMatchers = compileProxyMatchers(trustedProxies);
    }

    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "127.0.0.1";
        }
        String trimmedRemote = remoteAddr.trim();
        if (!isTrustedProxy(trimmedRemote)) {
            return trimmedRemote;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            String firstNonBlank = null;
            for (String raw : parts) {
                String trimmed = raw.trim();
                if (!trimmed.isBlank()) {
                    firstNonBlank = trimmed;
                    break;
                }
            }
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = parts[i].trim();
                if (!candidate.isBlank() && !isTrustedProxy(candidate)) {
                    return candidate;
                }
            }
            if (firstNonBlank != null) {
                return firstNonBlank;
            }
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return trimmedRemote;
    }

    private boolean isTrustedProxy(String ipAddress) {
        for (IpAddressMatcher matcher : trustedProxyMatchers) {
            try {
                if (matcher.matches(ipAddress)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed IP (e.g. spoofed header token or non-IP stub) and treat as untrusted.
            }
        }
        return false;
    }

    private static List<IpAddressMatcher> compileProxyMatchers(Collection<String> trustedProxies) {
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            return List.of();
        }
        return trustedProxies.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(IpAddressMatcher::new)
                .toList();
    }
}
