package za.co.capi.dispute.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import za.co.capi.dispute.domain.model.DisputeAttachment;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DisputeAttachmentRepository extends JpaRepository<DisputeAttachment, UUID> {
    List<DisputeAttachment> findByDisputeIdOrderByCreatedAtAsc(UUID disputeId);
    long countByDisputeId(UUID disputeId);

    @Query("""
            SELECT a.disputeId AS disputeId, COUNT(a.id) AS attachmentCount
            FROM DisputeAttachment a
            WHERE a.disputeId IN :disputeIds
            GROUP BY a.disputeId
            """)
    List<DisputeAttachmentCountProjection> countByDisputeIds(@Param("disputeIds") Collection<UUID> disputeIds);

    interface DisputeAttachmentCountProjection {
        UUID getDisputeId();
        long getAttachmentCount();
    }
}
