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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiterService.class);
    private static final String KEY_PREFIX = "capi:ratelimit:login:";
    private static final String KEY_IP_PREFIX = KEY_PREFIX + "ip:";
    private static final String KEY_ACCT_PREFIX = KEY_PREFIX + "acct:";
    private static final String KEY_LOCK_PREFIX = KEY_PREFIX + "lock:";

    private final StringRedisTemplate redisTemplate;
    private final int maxAccountFailures;
    private final long accountLockSeconds;
    private final int maxIpFailures;
    private final long ipWindowSeconds;

    private final Map<String, Deque<Instant>> localIpFailures = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> localAccountFailures = new ConcurrentHashMap<>();
    private final Map<String, Instant> localAccountLocks = new ConcurrentHashMap<>();

    public LoginRateLimiterService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Value("${app.security.login.max-account-failures:5}") int maxAccountFailures,
            @Value("${app.security.login.account-lock-seconds:900}") long accountLockSeconds,
            @Value("${app.security.login.max-ip-failures:20}") int maxIpFailures,
            @Value("${app.security.login.ip-window-seconds:300}") long ipWindowSeconds) {
        this.redisTemplate = redisTemplate;
        this.maxAccountFailures = maxAccountFailures;
        this.accountLockSeconds = accountLockSeconds;
        this.maxIpFailures = maxIpFailures;
        this.ipWindowSeconds = ipWindowSeconds;
    }

    public boolean isIpRateLimited(String ipAddress) {
        String ipKey = normalizeIp(ipAddress);
        if (redisTemplate != null) {
            try {
                String raw = redisTemplate.opsForValue().get(KEY_IP_PREFIX + ipKey);
                return raw != null && Long.parseLong(raw) >= maxIpFailures;
            } catch (Exception ex) {
                log.debug("Redis unreachable ({}); checking in-memory IP login limiter.", ex.getMessage());
            }
        }
        return isLocalIpRateLimited(ipKey);
    }

    public boolean isAccountTemporarilyLocked(String email) {
        String emailKey = normalizeEmail(email);
        if (redisTemplate != null) {
            try {
                return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_LOCK_PREFIX + emailKey));
            } catch (Exception ex) {
                log.debug("Redis unreachable ({}); checking in-memory account lock store.", ex.getMessage());
            }
        }
        return isLocalAccountLocked(emailKey);
    }

    public void recordFailure(String email, String ipAddress) {
        String ipKey = normalizeIp(ipAddress);
        String emailKey = normalizeEmail(email);

        recordIpFailureInternal(ipKey);
        recordAccountFailureInternal(emailKey);
    }

    public void recordIpFailureOnly(String ipAddress) {
        recordIpFailureInternal(normalizeIp(ipAddress));
    }

    public void resetAccountFailures(String email) {
        String emailKey = normalizeEmail(email);
        localAccountFailures.remove(emailKey);
        localAccountLocks.remove(emailKey);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(List.of(KEY_ACCT_PREFIX + emailKey, KEY_LOCK_PREFIX + emailKey));
            } catch (Exception ex) {
                log.debug("Redis unreachable ({}); cleared local account login counters only.", ex.getMessage());
            }
        }
    }

    public void clearLocalLimits() {
        localIpFailures.clear();
        localAccountFailures.clear();
        localAccountLocks.clear();
        if (redisTemplate != null) {
            try {
                Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception ignored) {
                // Local state already cleared; ignore if Redis is not running during tests.
            }
        }
    }

    private void recordIpFailureInternal(String ipKey) {
        if (redisTemplate != null) {
            try {
                String key = KEY_IP_PREFIX + ipKey;
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redisTemplate.expire(key, Duration.ofSeconds(ipWindowSeconds));
                }
                return;
            } catch (Exception ex) {
                log.debug("Redis unreachable ({}); falling back to in-memory IP failure counter.", ex.getMessage());
            }
        }
        recordLocalTimestamp(localIpFailures, ipKey, ipWindowSeconds);
    }

    private void recordAccountFailureInternal(String emailKey) {
        if (redisTemplate != null) {
            try {
                String acctKey = KEY_ACCT_PREFIX + emailKey;
                Long count = redisTemplate.opsForValue().increment(acctKey);
                if (count != null && count == 1L) {
                    redisTemplate.expire(acctKey, Duration.ofSeconds(accountLockSeconds));
                }
                if (count != null && count >= maxAccountFailures) {
                    redisTemplate.opsForValue().set(KEY_LOCK_PREFIX + emailKey, "1", Duration.ofSeconds(accountLockSeconds));
                }
                return;
            } catch (Exception ex) {
                log.debug("Redis unreachable ({}); falling back to in-memory account failure counter.", ex.getMessage());
            }
        }
        int failures = recordLocalTimestamp(localAccountFailures, emailKey, accountLockSeconds);
        if (failures >= maxAccountFailures) {
            localAccountLocks.put(emailKey, Instant.now().plusSeconds(accountLockSeconds));
        }
    }

    private boolean isLocalIpRateLimited(String ipKey) {
        Deque<Instant> timestamps = localIpFailures.get(ipKey);
        if (timestamps == null) {
            return false;
        }
        Instant cutoff = Instant.now().minusSeconds(ipWindowSeconds);
        synchronized (timestamps) {
            pruneBefore(timestamps, cutoff);
            return timestamps.size() >= maxIpFailures;
        }
    }

    private boolean isLocalAccountLocked(String emailKey) {
        Instant unlockAt = localAccountLocks.get(emailKey);
        if (unlockAt == null) {
            return false;
        }
        if (Instant.now().isBefore(unlockAt)) {
            return true;
        }
        localAccountLocks.remove(emailKey, unlockAt);
        return false;
    }

    private int recordLocalTimestamp(Map<String, Deque<Instant>> store, String key, long windowSeconds) {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(windowSeconds);
        Deque<Instant> timestamps = store.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            pruneBefore(timestamps, cutoff);
            timestamps.addLast(now);
            return timestamps.size();
        }
    }

    private static void pruneBefore(Deque<Instant> timestamps, Instant cutoff) {
        while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
            timestamps.pollFirst();
        }
    }

    private static String normalizeEmail(String email) {
        return email != null ? email.trim().toLowerCase() : "unknown";
    }

    private static String normalizeIp(String ipAddress) {
        return (ipAddress != null && !ipAddress.isBlank()) ? ipAddress.trim() : "unknown";
    }
}
