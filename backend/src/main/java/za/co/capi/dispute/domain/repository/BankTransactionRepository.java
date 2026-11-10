package za.co.capi.dispute.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.capi.dispute.domain.model.BankTransaction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {
    List<BankTransaction> findByCustomerIdOrderByTransactedAtDesc(UUID customerId);
    Optional<BankTransaction> findByTransactionReferenceIgnoreCase(String transactionReference);
}

