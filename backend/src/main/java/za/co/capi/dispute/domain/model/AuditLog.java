package za.co.capi.dispute.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.Instant;
import java.util.UUID;

/**
 * Application-level append-only audit log entity ({@code @Immutable} with {@code updatable = false} columns).
 * Note: Database-level {@code REVOKE UPDATE, DELETE} privileges are required for tamper-proof enforcement in production.
 */
@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_email", length = 160, updatable = false)
    private String actorEmail;

    @Column(nullable = false, length = 24, updatable = false)
    private String severity = "INFO";

    @Column(name = "event_type", nullable = false, length = 80, updatable = false)
    private String eventType;

    @Column(name = "entity_name", nullable = false, length = 80, updatable = false)
    private String entityName;

    @Column(name = "entity_id", nullable = false, length = 255, updatable = false)
    private String entityId;

    @Column(name = "ip_address", length = 64, updatable = false)
    private String ipAddress;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String metadata;

    @Column(nullable = false, updatable = false)
    private Instant timestamp = Instant.now();

    public AuditLog() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getActorId() {
        return actorId;
    }

    public void setActorId(UUID actorId) {
        this.actorId = actorId;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public void setActorEmail(String actorEmail) {
        this.actorEmail = actorEmail;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}

