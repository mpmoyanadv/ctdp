package za.co.capi.dispute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.BankTransaction;
import za.co.capi.dispute.domain.model.TransactionStatus;
import za.co.capi.dispute.domain.repository.AppUserRepository;
import za.co.capi.dispute.domain.repository.BankTransactionRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransactionIntegrityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private BankTransactionRepository transactionRepository;

    @MockBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setupMailMock() {
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    @Test
    void enforcesOwnershipDerivedFieldsAndNoDuplicateActiveDisputes() throws Exception {
        String customer1Token = loginAndExtractToken("customer@capi.test", "Password123!");
        String customer2Token = loginAndExtractToken("customer2@capi.test", "Password123!");
        String staffToken = loginAndExtractToken("staff@capi.test", "Password123!");

        AppUser customer1 = appUserRepository.findByEmailIgnoreCase("customer@capi.test").orElseThrow();

        BankTransaction settledTx = saveTx(
                customer1.getId(),
                "TXN-TEST-SETTLED-01",
                "Pick n Pay Rosebank",
                "CARD_POS",
                new BigDecimal("349.95"),
                TransactionStatus.SETTLED
        );

        // 1. Disputing another customer's transaction must fail with 403 Forbidden
        String otherCustomerBody = objectMapper.writeValueAsString(Map.of(
                "transactionId", settledTx.getId().toString(),
                "merchantName", "Pick n Pay Rosebank",
                "paymentRail", "CARD_POS",
                "category", "UNAUTHORIZED_TRANSACTION",
                "amount", 349.95,
                "reason", "Attempting to dispute another customer's transaction."
        ));
        mockMvc.perform(post("/api/v1/disputes")
                        .header("Authorization", "Bearer " + customer2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otherCustomerBody))
                .andExpect(status().isForbidden());

        // 2. Tampered merchantName, paymentRail, and amount are overridden by authoritative BankTransaction fields
        String tamperedBody = objectMapper.writeValueAsString(Map.of(
                "transactionId", settledTx.getId().toString(),
                "merchantName", "Tampered Merchant",
                "paymentRail", "PAYSHAP",
                "category", "UNAUTHORIZED_TRANSACTION",
                "amount", 99999.00,
                "reason", "Card was skimmed at terminal and debited without my consent."
        ));
        MvcResult createResult = mockMvc.perform(post("/api/v1/disputes")
                        .header("Authorization", "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchantName").value("Pick n Pay Rosebank"))
                .andExpect(jsonPath("$.paymentRail").value("CARD_POS"))
                .andExpect(jsonPath("$.amount").value(349.95))
                .andReturn();

        String disputeId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // 3. Duplicate active dispute on the same transaction (with different payload to bypass idempotency lock) -> 409 Conflict
        String duplicateBody = objectMapper.writeValueAsString(Map.of(
                "transactionId", settledTx.getId().toString(),
                "merchantName", "Pick n Pay Rosebank",
                "paymentRail", "CARD_POS",
                "category", "DUPLICATE_CHARGE",
                "amount", 349.95,
                "reason", "Submitting a second active dispute on the exact same settled transaction."
        ));
        mockMvc.perform(post("/api/v1/disputes")
                        .header("Authorization", "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateBody))
                .andExpect(status().isConflict());

        // 4. Once the dispute reaches REJECTED (terminal), re-opening as a linked dispute is allowed
        mockMvc.perform(patch("/api/v1/disputes/" + disputeId + "/status")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\",\"resolutionSummary\":\"Insufficient evidence initially\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(post("/api/v1/disputes/" + disputeId + "/reopen")
                        .header("Authorization", "Bearer " + customer1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Attaching SAPS case number and signed affidavit.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("LOGGED"))
                .andExpect(jsonPath("$.amount").value(349.95));
    }

    @Test
    void rejectsIneligiblePendingAndReversedTransactions() throws Exception {
        String customer1Token = loginAndExtractToken("customer@capi.test", "Password123!");
        AppUser customer1 = appUserRepository.findByEmailIgnoreCase("customer@capi.test").orElseThrow();

        BankTransaction pendingTx = saveTx(
                customer1.getId(),
                "TXN-TEST-PENDING-01",
                "Uber Trip Pending",
                "CARD_POS",
                new BigDecimal("180.00"),
                TransactionStatus.PENDING
        );
        BankTransaction reversedTx = saveTx(
                customer1.getId(),
                "TXN-TEST-REVERSED-01",
                "Takealot Reversed",
                "ONLINE_ECOMMERCE",
                new BigDecimal("650.00"),
                TransactionStatus.REVERSED
        );

        for (BankTransaction tx : new BankTransaction[]{pendingTx, reversedTx}) {
            String body = objectMapper.writeValueAsString(Map.of(
                    "transactionId", tx.getId().toString(),
                    "merchantName", tx.getMerchantName(),
                    "paymentRail", tx.getPaymentRail(),
                    "category", "UNAUTHORIZED_TRANSACTION",
                    "amount", tx.getAmount(),
                    "reason", "Attempting to dispute an ineligible transaction status."
            ));
            mockMvc.perform(post("/api/v1/disputes")
                            .header("Authorization", "Bearer " + customer1Token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict());
        }
    }

    private BankTransaction saveTx(
            java.util.UUID customerId,
            String ref,
            String merchant,
            String rail,
            BigDecimal amount,
            TransactionStatus status) {
        BankTransaction tx = new BankTransaction();
        tx.setCustomerId(customerId);
        tx.setTransactionReference(ref);
        tx.setMerchantName(merchant);
        tx.setPaymentRail(rail);
        tx.setAmount(amount);
        tx.setCurrency("ZAR");
        tx.setCardLastFour("4321");
        tx.setLocation("Johannesburg");
        tx.setTransactedAt(Instant.now());
        tx.setStatus(status);
        return transactionRepository.save(tx);
    }

    private String loginAndExtractToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }
}

