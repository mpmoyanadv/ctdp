package za.co.capi.dispute.domain.model;

public enum DisputeStatus {
    LOGGED,
    IN_REVIEW,
    PENDING_CUSTOMER_ACTION,
    PENDING_EXTERNAL_REVIEW,
    ESCALATED,
    RESOLVED,
    REFUNDED,
    REJECTED;

    public boolean isTerminal() {
        return this == RESOLVED || this == REFUNDED || this == REJECTED;
    }
}

