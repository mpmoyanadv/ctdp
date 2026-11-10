package za.co.capi.dispute.domain.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dispute_notes")
public class DisputeNote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "dispute_id", nullable = false)
    private UUID disputeId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "author_name", nullable = false, length = 160)
    private String authorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_role", nullable = false, length = 32)
    private UserRole authorRole;

    @Column(nullable = false, length = 3000)
    private String message;

    @Column(name = "internal_only", nullable = false)
    private boolean internalOnly = false;

    @Column(name = "requires_customer_reply", nullable = false)
    private boolean requiresCustomerReply = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public DisputeNote() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDisputeId() {
        return disputeId;
    }

    public void setDisputeId(UUID disputeId) {
        this.disputeId = disputeId;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public void setAuthorId(UUID authorId) {
        this.authorId = authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public UserRole getAuthorRole() {
        return authorRole;
    }

    public void setAuthorRole(UserRole authorRole) {
        this.authorRole = authorRole;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isInternalOnly() {
        return internalOnly;
    }

    public void setInternalOnly(boolean internalOnly) {
        this.internalOnly = internalOnly;
    }

    public boolean isRequiresCustomerReply() {
        return requiresCustomerReply;
    }

    public void setRequiresCustomerReply(boolean requiresCustomerReply) {
        this.requiresCustomerReply = requiresCustomerReply;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

