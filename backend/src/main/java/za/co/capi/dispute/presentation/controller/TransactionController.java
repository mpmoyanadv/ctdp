package za.co.capi.dispute.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.capi.dispute.application.service.TransactionService;
import za.co.capi.dispute.infrastructure.config.OpenApiConfig;
import za.co.capi.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capi.dispute.presentation.dto.ApiDtos.TransactionDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transactions")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "2. Retail Customer Transactions & Settlement Feed")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Operation(
            summary = "List authenticated customer's settled bank transactions",
            description = """
                    Returns the chronological ledger feed of settled transactions belonging to the authenticated `CUSTOMER` across domestic payment rails:
                    - **`PAYSHAP`** — Instant interbank proxy settlements (`pacs.008`) cleared via BankservAfrica
                    - **`DEBIT_ORDER`** — DebiCheck / EFT recurring debit order collections (`pain.008` / `camt.053`)
                    - **`CARD_POS`** — Point-of-Sale card present transactions
                    - **`ONLINE_ECOMMERCE`** — Card-not-present 3D Secure online transactions
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settled customer transactions retrieved in reverse-chronological order"),
            @ApiResponse(responseCode = "401", description = "Missing, expired, or fraud-revoked JWT token")
    })
    @GetMapping
    public ResponseEntity<List<TransactionDto>> listMyTransactions(
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return ResponseEntity.ok(transactionService.getCustomerTransactions(principal));
    }
}
