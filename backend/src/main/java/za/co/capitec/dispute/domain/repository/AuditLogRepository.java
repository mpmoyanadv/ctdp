package za.co.capitec.dispute.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.capitec.dispute.domain.model.AuditLog;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findAllByOrderByTimestampDesc();
    List<AuditLog> findByEventTypeOrderByTimestampDesc(String eventType);
}

