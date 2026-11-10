package za.co.capi.dispute.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.capi.dispute.domain.model.StaffWorkSession;

import java.util.List;
import java.util.UUID;

public interface StaffWorkSessionRepository extends JpaRepository<StaffWorkSession, Long> {
    List<StaffWorkSession> findByDisputeIdAndCompletedAtIsNull(UUID disputeId);
    List<StaffWorkSession> findAllByOrderByStartedAtDesc();
}

