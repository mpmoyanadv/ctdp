package za.co.capitec.dispute.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.capitec.dispute.domain.model.FraudAlertToken;

import java.util.Optional;
import java.util.UUID;

public interface FraudAlertTokenRepository extends JpaRepository<FraudAlertToken, UUID> {
    Optional<FraudAlertToken> findByToken(String token);
}

