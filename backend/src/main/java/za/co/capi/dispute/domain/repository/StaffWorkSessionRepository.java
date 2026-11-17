package za.co.capi.dispute.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import za.co.capi.dispute.domain.model.StaffWorkSession;

import java.util.List;
import java.util.UUID;

public interface StaffWorkSessionRepository extends JpaRepository<StaffWorkSession, Long> {
    List<StaffWorkSession> findByDisputeIdAndCompletedAtIsNull(UUID disputeId);
    List<StaffWorkSession> findAllByOrderByStartedAtDesc();
    Page<StaffWorkSession> findAllByOrderByStartedAtDesc(Pageable pageable);

    @Query("""
            SELECT s.staffId AS staffId,
                   SUM(CASE WHEN s.completedAt IS NOT NULL AND s.totalDurationSeconds IS NOT NULL THEN 1 ELSE 0 END) AS completedSessions,
                   SUM(CASE WHEN s.completedAt IS NULL THEN 1 ELSE 0 END) AS activeSessions,
                   COALESCE(SUM(CASE WHEN s.completedAt IS NOT NULL AND s.totalDurationSeconds IS NOT NULL THEN s.totalDurationSeconds ELSE 0 END), 0) AS totalDurationSeconds,
                   COUNT(DISTINCT s.disputeId) AS distinctDisputesHandled
            FROM StaffWorkSession s
            GROUP BY s.staffId
            """)
    List<StaffProductivityAggregateProjection> aggregateByStaff();

    interface StaffProductivityAggregateProjection {
        UUID getStaffId();
        Long getCompletedSessions();
        Long getActiveSessions();
        Long getTotalDurationSeconds();
        Long getDistinctDisputesHandled();
    }
}
