package za.co.capi.dispute.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.capi.dispute.domain.model.DisputeAttachment;

import java.util.List;
import java.util.UUID;

public interface DisputeAttachmentRepository extends JpaRepository<DisputeAttachment, UUID> {
    List<DisputeAttachment> findByDisputeIdOrderByCreatedAtAsc(UUID disputeId);
    long countByDisputeId(UUID disputeId);
}

