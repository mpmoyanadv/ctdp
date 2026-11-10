package za.co.capitec.dispute.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "disputes")
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "dispute_reference", nullable = false, unique = true, length = 40)
    private String disputeReference;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "merchant_name", nullable = false, length = 180)
    private String merchantName;

    @Column(name = "payment_rail", length = 48)
    private String paymentRail;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "assigned_user_id")
    private UUID assignedUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assigned_domain", nullable = false, length = 40)
    private UserDomain assignedDomain = UserDomain.INTERNAL_CAPITEC;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DisputeStatus status = DisputeStatus.LOGGED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 60)
    private DisputeCategory category;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 2000)
    private String reason;

    @Column(name = "resolution_summary", length = 2000)
    private String resolutionSummary;

    @Column(name = "original_dispute_id")
    private UUID originalDisputeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public Dispute() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getDisputeReference() {
        return disputeReference;
    }

    public void setDisputeReference(String disputeReference) {
        this.disputeReference = disputeReference;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getPaymentRail() {
        return paymentRail;
    }

    public void setPaymentRail(String paymentRail) {
        this.paymentRail = paymentRail;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public UUID getAssignedUserId() {
        return assignedUserId;
    }

    public void setAssignedUserId(UUID assignedUserId) {
        this.assignedUserId = assignedUserId;
    }

    public UserDomain getAssignedDomain() {
        return assignedDomain;
    }

    public void setAssignedDomain(UserDomain assignedDomain) {
        this.assignedDomain = assignedDomain;
    }

    public DisputeStatus getStatus() {
        return status;
    }

    public void setStatus(DisputeStatus status) {
        this.status = status;
    }

    public DisputeCategory getCategory() {
        return category;
    }

    public void setCategory(DisputeCategory category) {
        this.category = category;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getResolutionSummary() {
        return resolutionSummary;
    }

    public void setResolutionSummary(String resolutionSummary) {
        this.resolutionSummary = resolutionSummary;
    }

    public UUID getOriginalDisputeId() {
        return originalDisputeId;
    }

    public void setOriginalDisputeId(UUID originalDisputeId) {
        this.originalDisputeId = originalDisputeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}

