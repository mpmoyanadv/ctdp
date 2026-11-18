package za.co.capi.dispute.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.co.capi.dispute.domain.model.*;
import za.co.capi.dispute.domain.repository.AppUserRepository;
import za.co.capi.dispute.domain.repository.BankTransactionRepository;
import za.co.capi.dispute.domain.repository.DisputeRepository;
import za.co.capi.dispute.domain.repository.StaffWorkSessionRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Seeds fixed demo accounts, transactions, and sample disputes.
 * Restricted to {@code demo} and {@code dev} profiles — fixed credentials here
 * are strictly for local/demo use and are unsuitable for production.
 */
@Component
@Profile({"demo", "dev"})
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    /** Demo-only password; never use in production. */
    public static final String DEFAULT_PASSWORD = "Password123!";

    private final AppUserRepository userRepository;
    private final BankTransactionRepository transactionRepository;
    private final DisputeRepository disputeRepository;
    private final StaffWorkSessionRepository workSessionRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(
            AppUserRepository userRepository,
            BankTransactionRepository transactionRepository,
            DisputeRepository disputeRepository,
            StaffWorkSessionRepository workSessionRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.disputeRepository = disputeRepository;
        this.workSessionRepository = workSessionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("Database already seeded ({} users present).", userRepository.count());
            return;
        }

        String encodedPassword = passwordEncoder.encode(DEFAULT_PASSWORD);

        AppUser customer1 = createUser(
                "customer@capi.test",
                "Thabo Mokoena",
                "8501015800082",
                "+27825550192",
                "1548920341",
                encodedPassword,
                UserRole.CUSTOMER,
                UserDomain.CUSTOMER,
                null
        );

        AppUser customer2 = createUser(
                "customer2@capi.test",
                "Naledi Dlamini",
                "9104120842087",
                "+27834419901",
                "1620449812",
                encodedPassword,
                UserRole.CUSTOMER,
                UserDomain.CUSTOMER,
                null
        );

        AppUser customer3 = createUser(
                "customer3@capi.test",
                "Tendai Moyo",
                "ZW4829104",
                "+27718842109",
                "1740882190",
                encodedPassword,
                UserRole.CUSTOMER,
                UserDomain.CUSTOMER,
                null
        );

        AppUser customer4 = createUser(
                "customer4@capi.test",
                "Chidinma Okafor",
                "NG7739201",
                "+27649923104",
                "1890334512",
                encodedPassword,
                UserRole.CUSTOMER,
                UserDomain.CUSTOMER,
                null
        );

        AppUser staff1 = createUser(
                "staff@capi.test",
                "Sipho Ndlovu",
                null,
                "+27219411000",
                null,
                encodedPassword,
                UserRole.STAFF,
                UserDomain.INTERNAL_CAPI,
                "Call Centre KYC & Intake Agent"
        );

        createUser(
                "staff2@capi.test",
                "Zanele Mbeki",
                null,
                "+27219411001",
                null,
                encodedPassword,
                UserRole.STAFF,
                UserDomain.INTERNAL_CAPI,
                "Dispute Investigator"
        );

        createUser(
                "affiliate@bankserv.test",
                "Johan van Wyk",
                null,
                "+27114974000",
                null,
                encodedPassword,
                UserRole.AFFILIATE,
                UserDomain.PARTNER_BANKSERV,
                "BankservAfrica"
        );

        createUser(
                "admin@capi.test",
                "Lerato Khumalo",
                null,
                "+27219411999",
                null,
                encodedPassword,
                UserRole.ADMIN,
                UserDomain.INTERNAL_CAPI,
                "Supervisor / System Administrator"
        );

        Instant now = Instant.now();

        createTransaction(customer1.getId(), "TXN-2026-9001", "Shell Braamfontein", "CARD_POS",
                new BigDecimal("850.00"), "4821", "Braamfontein, Johannesburg", now.minus(4, ChronoUnit.HOURS));
        createTransaction(customer1.getId(), "TXN-2026-9002", "Checkers Sixty60", "ONLINE_ECOMMERCE",
                new BigDecimal("450.00"), "4821", "Sandton, Gauteng", now.minus(1, ChronoUnit.DAYS));
        BankTransaction payshapTx = createTransaction(customer1.getId(), "TXN-2026-9003", "PayShap Transfer", "PAYSHAP",
                new BigDecimal("1200.00"), "SHAP", "Instant Interbank Rail", now.minus(2, ChronoUnit.DAYS));
        BankTransaction dstvTx = createTransaction(customer1.getId(), "TXN-2026-9004", "MultiChoice DStv Subscription", "DEBIT_ORDER",
                new BigDecimal("899.00"), "0341", "Randburg, Gauteng", now.minus(4, ChronoUnit.DAYS));
        createTransaction(customer1.getId(), "TXN-2026-9005", "Sandton City ATM #042", "ATM_WITHDRAWAL",
                new BigDecimal("1500.00"), "4821", "Sandton City Mall, Gauteng", now.minus(2, ChronoUnit.HOURS));

        createTransaction(customer2.getId(), "TXN-2026-9101", "Woolworths Food Rosebank", "CARD_POS",
                new BigDecimal("620.00"), "7739", "Rosebank, Johannesburg", now.minus(6, ChronoUnit.HOURS));
        createTransaction(customer2.getId(), "TXN-2026-9102", "Engen 1-Stop Midrand", "CARD_POS",
                new BigDecimal("710.00"), "7739", "Midrand, Gauteng", now.minus(2, ChronoUnit.DAYS));

        createTransaction(customer3.getId(), "TXN-2026-9201", "OR Tambo Duty Free Mall", "CARD_POS",
                new BigDecimal("1450.00"), "2190", "Kempton Park, Gauteng", now.minus(5, ChronoUnit.HOURS));
        createTransaction(customer3.getId(), "TXN-2026-9202", "Mukuru Cross-Border Remittance", "EFT",
                new BigDecimal("2500.00"), "EFT", "International Remittance Rail", now.minus(1, ChronoUnit.DAYS));
        createTransaction(customer3.getId(), "TXN-2026-9203", "Rosebank Gautrain ATM #011", "ATM_WITHDRAWAL",
                new BigDecimal("1000.00"), "2190", "Rosebank, Johannesburg", now.minus(3, ChronoUnit.DAYS));

        createTransaction(customer4.getId(), "TXN-2026-9301", "V&A Waterfront Hotel Cape Town", "CARD_POS",
                new BigDecimal("3200.00"), "4512", "Waterfront, Cape Town", now.minus(7, ChronoUnit.HOURS));
        createTransaction(customer4.getId(), "TXN-2026-9302", "Uber Rides South Africa", "ONLINE_ECOMMERCE",
                new BigDecimal("380.00"), "4512", "Cape Town, Western Cape", now.minus(1, ChronoUnit.DAYS));
        createTransaction(customer4.getId(), "TXN-2026-9303", "Discovery Health International Debit", "DEBIT_ORDER",
                new BigDecimal("1150.00"), "4512", "Sandton, Gauteng", now.minus(4, ChronoUnit.DAYS));

        // Seed an open PayShap dispute ready for external partner Bankserv delegation
        Dispute payshapDispute = new Dispute();
        payshapDispute.setDisputeReference("DSP-2026-10042");
        payshapDispute.setTransactionId(payshapTx.getId());
        payshapDispute.setMerchantName("PayShap Transfer");
        payshapDispute.setPaymentRail("PAYSHAP");
        payshapDispute.setCustomerId(customer1.getId());
        payshapDispute.setAssignedDomain(UserDomain.INTERNAL_CAPI);
        payshapDispute.setStatus(DisputeStatus.LOGGED);
        payshapDispute.setCategory(DisputeCategory.PAYSHAP_WRONG_RECIPIENT);
        payshapDispute.setAmount(new BigDecimal("1200.00"));
        payshapDispute.setReason("Unrecognized PayShap instant proxy transfer of R1,200.00 debited from my savings account without authorization.");
        payshapDispute.setCreatedAt(now.minus(1, ChronoUnit.DAYS));
        disputeRepository.save(payshapDispute);

        // Seed a closed historic dispute (REFUNDED) so historic disputed transactions are visible immediately
        Dispute historicDispute = new Dispute();
        historicDispute.setDisputeReference("DSP-2026-09811");
        historicDispute.setTransactionId(dstvTx.getId());
        historicDispute.setMerchantName("MultiChoice DStv Subscription");
        historicDispute.setPaymentRail("DEBIT_ORDER");
        historicDispute.setCustomerId(customer1.getId());
        historicDispute.setAssignedDomain(UserDomain.INTERNAL_CAPI);
        historicDispute.setAssignedUserId(staff1.getId());
        historicDispute.setStatus(DisputeStatus.REFUNDED);
        historicDispute.setCategory(DisputeCategory.DUPLICATE_CHARGE);
        historicDispute.setAmount(new BigDecimal("899.00"));
        historicDispute.setReason("Debited twice in the same billing cycle for monthly DStv Compact Plus subscription.");
        historicDispute.setResolutionSummary("Verified duplicate DebiCheck mandate presentation; full R899.00 reversal credited to Global One account.");
        historicDispute.setCreatedAt(now.minus(5, ChronoUnit.DAYS));
        historicDispute.setResolvedAt(now.minus(3, ChronoUnit.DAYS));
        Dispute savedHistoric = disputeRepository.save(historicDispute);

        StaffWorkSession historicSession = new StaffWorkSession();
        historicSession.setDisputeId(savedHistoric.getId());
        historicSession.setStaffId(staff1.getId());
        historicSession.setStartedAt(now.minus(4, ChronoUnit.DAYS));
        historicSession.setCompletedAt(now.minus(3, ChronoUnit.DAYS));
        historicSession.setTotalDurationSeconds(1860L);
        workSessionRepository.save(historicSession);

        log.info("Seeded Cap Trans Dsp Ptl with 8 test accounts (including 2 foreign passport customers), 13 transactions, and 2 initial disputes (active + historic).");
    }

    private AppUser createUser(
            String email,
            String fullName,
            String saId,
            String phone,
            String account,
            String passwordHash,
            UserRole role,
            UserDomain domain,
            String partnerInstitution) {

        AppUser user = new AppUser();
        user.setEmail(email);
        user.setFullName(fullName);
        user.setSaIdNumber(saId);
        user.setPhoneNumber(phone);
        user.setAccountNumber(account);
        user.setPasswordHash(passwordHash);
        user.setRole(role);
        user.setUserDomain(domain);
        user.setPartnerInstitution(partnerInstitution);
        user.setLocked(false);
        return userRepository.save(user);
    }

    private BankTransaction createTransaction(
            java.util.UUID customerId,
            String ref,
            String merchant,
            String rail,
            BigDecimal amount,
            String cardLastFour,
            String location,
            Instant transactedAt) {

        BankTransaction tx = new BankTransaction();
        tx.setCustomerId(customerId);
        tx.setTransactionReference(ref);
        tx.setMerchantName(merchant);
        tx.setPaymentRail(rail);
        tx.setAmount(amount);
        tx.setCurrency("ZAR");
        tx.setCardLastFour(cardLastFour);
        tx.setLocation(location);
        tx.setTransactedAt(transactedAt);
        return transactionRepository.save(tx);
    }
}

