package za.co.capitec.dispute.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.capitec.dispute.domain.model.DisputeNote;

import java.util.List;
import java.util.UUID;

public interface DisputeNoteRepository extends JpaRepository<DisputeNote, UUID> {
    List<DisputeNote> findByDisputeIdOrderByCreatedAtAsc(UUID disputeId);
    List<DisputeNote> findByDisputeIdAndInternalOnlyFalseOrderByCreatedAtAsc(UUID disputeId);
}

