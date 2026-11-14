package za.co.capi.dispute.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import za.co.capi.dispute.domain.model.PasswordResetOtp;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, UUID> {

    Optional<PasswordResetOtp> findTopByEmailIgnoreCaseAndUsedFalseOrderByCreatedAtDesc(String email);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PasswordResetOtp o SET o.used = true WHERE o.userId = :userId AND o.used = false")
    int invalidateActiveOtpsForUser(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PasswordResetOtp o SET o.used = true "
            + "WHERE o.id = :id AND o.used = false AND o.expiresAt > :now AND o.failedAttempts < :maxAttempts")
    int consumeOtpAtomically(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PasswordResetOtp o SET o.failedAttempts = o.failedAttempts + 1, "
            + "o.used = CASE WHEN (o.failedAttempts + 1) >= :maxAttempts THEN true ELSE o.used END "
            + "WHERE o.id = :id AND o.used = false")
    int incrementFailedAttempts(@Param("id") UUID id, @Param("maxAttempts") int maxAttempts);
}
