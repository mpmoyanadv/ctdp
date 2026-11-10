package za.co.capitec.dispute.infrastructure.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import za.co.capitec.dispute.presentation.dto.ApiDtos.CreateDisputeRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyLockService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyLockService.class);
    private static final String KEY_PREFIX = "capitec:idempotency:dispute:";

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    // Fallback in-memory TTL store when Redis daemon is unreachable (e.g., H2 JUnit test profile)
    private final Map<String, Instant> localLockFallback = new ConcurrentHashMap<>();
    private volatile boolean redisAvailable = true;

    public IdempotencyLockService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Value("${app.idempotency.ttl-seconds:300}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Computes a deterministic idempotency lock key for a dispute submission.
     * Uses the explicit X-Idempotency-Key header when provided, or derives a SHA-256
     * fingerprint from (customerId, transactionId, merchantName, category, amount, reason).
     */
    public String buildDisputeLockKey(UUID customerId, CreateDisputeRequest request, String explicitIdempotencyKey) {
        if (explicitIdempotencyKey != null && !explicitIdempotencyKey.isBlank()) {
            return KEY_PREFIX + "key:" + customerId + ":" + explicitIdempotencyKey.trim();
        }
        String canonicalPayload = String.join("|",
                customerId.toString(),
                request.transactionId() != null ? request.transactionId().toString() : "NO_TX",
                request.merchantName() != null ? request.merchantName().trim().toLowerCase() : "",
                request.category() != null ? request.category().name() : "",
                request.amount() != null ? request.amount().stripTrailingZeros().toPlainString() : "0",
                request.reason() != null ? request.reason().trim().toLowerCase() : ""
        );
        return KEY_PREFIX + "fp:" + customerId + ":" + sha256Short(canonicalPayload);
    }

    /**
     * Atomically acquires an idempotency lock in Redis using SET NX EX (or in-memory fallback).
     *
     * @return true if the lock was newly acquired; false if a duplicate request is already locked within the TTL window.
     */
    public boolean tryAcquireLock(String lockKey, String metadataValue) {
        if (redisTemplate != null) {
            try {
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, metadataValue, Duration.ofSeconds(ttlSeconds));
                redisAvailable = true;
                return Boolean.TRUE.equals(acquired);
            } catch (Exception ex) {
                redisAvailable = false;
                log.debug("Redis unreachable ({}); falling back to in-memory idempotency lock store.", ex.getMessage());
            }
        }
        return tryAcquireLocalFallback(lockKey);
    }

    /**
     * Releases an acquired idempotency lock if a downstream validation or persistence error occurs
     * before the dispute transaction commits.
     */
    public void releaseLock(String lockKey) {
        if (lockKey == null) {
            return;
        }
        localLockFallback.remove(lockKey);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(lockKey);
            } catch (Exception ignored) {
                // Ignore if Redis is offline in test mode
            }
        }
    }

    public boolean isRedisAvailable() {
        return redisAvailable && redisTemplate != null;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    private synchronized boolean tryAcquireLocalFallback(String lockKey) {
        Instant now = Instant.now();
        localLockFallback.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
        Instant existingExpiry = localLockFallback.get(lockKey);
        if (existingExpiry != null && existingExpiry.isAfter(now)) {
            return false;
        }
        localLockFallback.put(lockKey, now.plusSeconds(ttlSeconds));
        return true;
    }

    private String sha256Short(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}

