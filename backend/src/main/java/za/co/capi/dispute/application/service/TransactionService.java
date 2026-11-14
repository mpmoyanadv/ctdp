package za.co.capi.dispute.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.capi.dispute.domain.repository.BankTransactionRepository;
import za.co.capi.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capi.dispute.presentation.dto.ApiDtos.TransactionDto;

import java.util.List;

@Service
public class TransactionService {

    private final BankTransactionRepository transactionRepository;

    public TransactionService(BankTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public List<TransactionDto> getCustomerTransactions(AppUserPrincipal customer) {
        return transactionRepository.findByCustomerIdOrderByTransactedAtDesc(customer.getId())
                .stream()
                .map(t -> new TransactionDto(
                        t.getId(),
                        t.getTransactionReference(),
                        t.getMerchantName(),
                        t.getPaymentRail(),
                        t.getAmount(),
                        t.getCurrency(),
                        t.getCardLastFour(),
                        t.getLocation(),
                        t.getTransactedAt(),
                        t.getStatus()
                ))
                .toList();
    }
}

