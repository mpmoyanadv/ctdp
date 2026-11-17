package za.co.capi.dispute.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import za.co.capi.dispute.domain.model.Dispute;
import za.co.capi.dispute.domain.model.DisputeStatus;
import za.co.capi.dispute.domain.model.UserDomain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {
    Page<Dispute> findByCustomerId(UUID customerId, Pageable pageable);
    Page<Dispute> findByCustomerIdAndStatus(UUID customerId, DisputeStatus status, Pageable pageable);
    Page<Dispute> findByAssignedDomain(UserDomain assignedDomain, Pageable pageable);
    Page<Dispute> findByAssignedDomainAndStatus(UserDomain assignedDomain, DisputeStatus status, Pageable pageable);
    Page<Dispute> findByStatus(DisputeStatus status, Pageable pageable);
    List<Dispute> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
    List<Dispute> findByAssignedDomainOrderByCreatedAtDesc(UserDomain assignedDomain);
    List<Dispute> findAllByOrderByCreatedAtDesc();
    List<Dispute> findByOriginalDisputeIdOrderByCreatedAtDesc(UUID originalDisputeId);
    boolean existsByTransactionIdAndStatusNotIn(UUID transactionId, Collection<DisputeStatus> statuses);

    @Query(value = "SELECT nextval('dispute_ref_seq')", nativeQuery = true)
    long getNextDisputeReferenceSequence();
}

