package za.co.capi.dispute.domain.model;

public enum TransactionStatus {
    SETTLED,
    PENDING,
    REVERSED;

    public boolean isEligibleForDispute() {
        return this == SETTLED;
    }
}

