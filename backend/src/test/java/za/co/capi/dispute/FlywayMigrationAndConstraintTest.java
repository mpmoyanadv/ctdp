package za.co.capi.dispute;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.BankTransaction;
import za.co.capi.dispute.domain.model.Dispute;
import za.co.capi.dispute.domain.model.DisputeCategory;
import za.co.capi.dispute.domain.model.TransactionStatus;
import za.co.capi.dispute.domain.model.UserDomain;
import za.co.capi.dispute.domain.model.UserRole;
import za.co.capi.dispute.domain.repository.AppUserRepository;
import za.co.capi.dispute.domain.repository.BankTransactionRepository;
import za.co.capi.dispute.domain.repository.DisputeRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles({"demo", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FlywayMigrationAndConstraintTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private BankTransactionRepository transactionRepository;

    @Autowired
    private DisputeRepository disputeRepository;

    @MockBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setupMailMock() {
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    @Test
    void flywayAppliesCleanMigrationsAndEnforcesIntegrityConstraints() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();

        // Duplicate email violates uq_app_users_email
        AppUser duplicateUser = new AppUser();
        duplicateUser.setEmail("customer@capi.test");
        duplicateUser.setFullName("Duplicate User");
        duplicateUser.setPasswordHash("hashed");
        duplicateUser.setRole(UserRole.CUSTOMER);
        duplicateUser.setUserDomain(UserDomain.CUSTOMER);
        assertThatThrownBy(() -> appUserRepository.saveAndFlush(duplicateUser))
                .isInstanceOf(DataIntegrityViolationException.class);

        AppUser customer = appUserRepository.findByEmailIgnoreCase("customer@capi.test").orElseThrow();

        // Duplicate transaction_reference violates uq_bank_transactions_ref
        BankTransaction tx1 = new BankTransaction();
        tx1.setCustomerId(customer.getId());
        tx1.setTransactionReference("TXN-FLYWAY-UNIQ-01");
        tx1.setMerchantName("Test Merchant");
        tx1.setPaymentRail("CARD_POS");
        tx1.setAmount(new BigDecimal("100.00"));
        tx1.setTransactedAt(Instant.now());
        tx1.setStatus(TransactionStatus.SETTLED);
        transactionRepository.saveAndFlush(tx1);

        BankTransaction tx2 = new BankTransaction();
        tx2.setCustomerId(customer.getId());
        tx2.setTransactionReference("TXN-FLYWAY-UNIQ-01");
        tx2.setMerchantName("Test Merchant");
        tx2.setPaymentRail("CARD_POS");
        tx2.setAmount(new BigDecimal("100.00"));
        tx2.setTransactedAt(Instant.now());
        tx2.setStatus(TransactionStatus.SETTLED);
        assertThatThrownBy(() -> transactionRepository.saveAndFlush(tx2))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Foreign key violation when referencing a non-existent customer_id
        Dispute orphanDispute = new Dispute();
        orphanDispute.setDisputeReference("DSP-FLYWAY-FK-01");
        orphanDispute.setMerchantName("Orphan Merchant");
        orphanDispute.setCustomerId(UUID.randomUUID());
        orphanDispute.setCategory(DisputeCategory.UNAUTHORIZED_TRANSACTION);
        orphanDispute.setAmount(new BigDecimal("50.00"));
        orphanDispute.setReason("Testing foreign key enforcement");
        assertThatThrownBy(() -> disputeRepository.saveAndFlush(orphanDispute))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Check constraint chk_disputes_amount_positive rejects amount <= 0
        Dispute nonPositiveDispute = new Dispute();
        nonPositiveDispute.setDisputeReference("DSP-FLYWAY-CHK-01");
        nonPositiveDispute.setMerchantName("Check Test Merchant");
        nonPositiveDispute.setCustomerId(customer.getId());
        nonPositiveDispute.setCategory(DisputeCategory.UNAUTHORIZED_TRANSACTION);
        nonPositiveDispute.setAmount(new BigDecimal("-1.00"));
        nonPositiveDispute.setReason("Testing positive amount check constraint");
        assertThatThrownBy(() -> disputeRepository.saveAndFlush(nonPositiveDispute))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

