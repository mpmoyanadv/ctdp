package za.co.capi.dispute.infrastructure.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OtpRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(OtpRateLimiterService.class);
    private static final String KEY_PREFIX = "capi:ratelimit:otp:";

    private final StringRedisTemplate redisTemplate;
    private final int maxAttemptsPerWindow;
    private final long windowSeconds;
    private final Map<String, Deque<Instant>> localAttempts = new ConcurrentHashMap<>();

    public OtpRateLimiterService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Value("${app.security.otp.rate-limit-max-attempts:5}") int maxAttemptsPerWindow,
            @Value("${app.security.otp.rate-limit-window-seconds:60}") long windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.maxAttemptsPerWindow = maxAttemptsPerWindow;
        this.windowSeconds = windowSeconds;
    }

    /**
     * Checks and records an OTP verification attempt for the given email and IP.
     *
     * @return true if the attempt is allowed within the configured rate-limit window; false if throttled.
     */
    public boolean tryConsumeAttempt(String email, String ipAddress) {
        String normalizedEmail = email != null ? email.trim().toLowerCase() : "unknown";
        String normalizedIp = ipAddress != null ? ipAddress.trim() : "unknown";
        String key = KEY_PREFIX + normalizedEmail + ":" + normalizedIp;

        if (redisTemplate != null) {
            try {
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
                }
                return count == null || count <= maxAttemptsPerWindow;
            } catch (Exception ex) {
                log.debug("Redis unreachable ({}); falling back to in-memory OTP rate limiter.", ex.getMessage());
            }
        }
        return tryConsumeLocal(key);
    }

    private boolean tryConsumeLocal(String key) {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(windowSeconds);
        Deque<Instant> timestamps = localAttempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxAttemptsPerWindow) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    public void clearLocalLimits() {
        localAttempts.clear();
    }
}

