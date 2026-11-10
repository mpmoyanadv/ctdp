package za.co.capi.dispute.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.capi.dispute.domain.model.Dispute;
import za.co.capi.dispute.domain.model.UserDomain;

import java.util.List;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {
    List<Dispute> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
    List<Dispute> findByAssignedDomainOrderByCreatedAtDesc(UserDomain assignedDomain);
    List<Dispute> findAllByOrderByCreatedAtDesc();
    List<Dispute> findByOriginalDisputeIdOrderByCreatedAtDesc(UUID originalDisputeId);
}

