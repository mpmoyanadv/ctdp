package za.co.capi.dispute.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import za.co.capi.dispute.domain.model.AuditLog;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findByEventType(String eventType, Pageable pageable);
    List<AuditLog> findAllByOrderByTimestampDesc();
    List<AuditLog> findByEventTypeOrderByTimestampDesc(String eventType);
}

