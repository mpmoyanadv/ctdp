package za.co.capi.dispute.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.capi.dispute.domain.model.AuditLog;
import za.co.capi.dispute.domain.repository.AuditLogRepository;
import za.co.capi.dispute.infrastructure.messaging.KafkaEventService;
import za.co.capi.dispute.infrastructure.security.ClientIpResolver;
import za.co.capi.dispute.presentation.dto.ApiDtos.AuditLogDto;
import za.co.capi.dispute.presentation.dto.ApiDtos.PageResponseDto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AuditService {

    private static final int MAX_CSV_EXPORT_ROWS = 10_000;
    private static final Set<String> REQUIRES_NEW_SECURITY_EVENTS = Set.of(
            "UNAUTHORIZED_ACCESS_ATTEMPT",
            "LOGIN_BLOCKED_LOCKED",
            "LOGIN_FAILED_BAD_CREDENTIALS",
            "LOGIN_FAILED_UNKNOWN_USER",
            "LOGIN_RATE_LIMITED_IP",
            "OTP_VERIFICATION_FAILED",
            "OTP_VERIFICATION_RATE_LIMITED",
            "IDEMPOTENCY_DUPLICATE_PREVENTED"
    );

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final KafkaEventService kafkaEventService;
    private final ClientIpResolver clientIpResolver;
    private final TransactionTemplate requiresNewTx;

    @Autowired
    public AuditService(
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            KafkaEventService kafkaEventService,
            ClientIpResolver clientIpResolver,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.kafkaEventService = kafkaEventService;
        this.clientIpResolver = clientIpResolver;
        if (transactionManager != null) {
            TransactionTemplate tpl = new TransactionTemplate(transactionManager);
            tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = tpl;
        } else {
            this.requiresNewTx = null;
        }
    }

    public AuditService(
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            KafkaEventService kafkaEventService) {
        this(auditLogRepository, objectMapper, kafkaEventService, new ClientIpResolver(List.of()), null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditLog logEvent(
            UUID actorId,
            String actorEmail,
            String severity,
            String eventType,
            String entityName,
            String entityId,
            String ipAddress,
            Map<String, Object> metadataMap) {

        boolean isolateInNewTx = requiresNewTx != null
                && eventType != null
                && REQUIRES_NEW_SECURITY_EVENTS.contains(eventType);

        if (isolateInNewTx) {
            return requiresNewTx.execute(status ->
                    persistAndPublishAudit(actorId, actorEmail, severity, eventType, entityName, entityId, ipAddress, metadataMap));
        }
        return persistAndPublishAudit(actorId, actorEmail, severity, eventType, entityName, entityId, ipAddress, metadataMap);
    }

    private AuditLog persistAndPublishAudit(
            UUID actorId,
            String actorEmail,
            String severity,
            String eventType,
            String entityName,
            String entityId,
            String ipAddress,
            Map<String, Object> metadataMap) {

        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorEmail(actorEmail);
        entry.setSeverity(severity != null ? severity : "INFO");
        entry.setEventType(eventType);
        entry.setEntityName(entityName);
        entry.setEntityId(entityId);
        entry.setIpAddress(ipAddress != null ? ipAddress : "unknown");
        entry.setMetadata(serializeMetadata(metadataMap));
        entry.setTimestamp(Instant.now());
        AuditLog saved = auditLogRepository.save(entry);

        kafkaEventService.publishSecurityAuditEvent(
                eventType,
                entityId != null ? entityId : "SYSTEM",
                Map.of(
                        "auditId", saved.getId() != null ? saved.getId() : 0L,
                        "severity", saved.getSeverity(),
                        "actorEmail", actorEmail != null ? actorEmail : "ANONYMOUS",
                        "entityName", entityName != null ? entityName : "System",
                        "entityId", entityId != null ? entityId : "N/A",
                        "ipAddress", saved.getIpAddress()
                )
        );
        return saved;
    }

    @Transactional(readOnly = true)
    public PageResponseDto<AuditLogDto> getLogsPage(String eventTypeFilter, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id")));

        Page<AuditLog> logsPage = (eventTypeFilter != null && !eventTypeFilter.isBlank())
                ? auditLogRepository.findByEventType(eventTypeFilter.trim(), pageable)
                : auditLogRepository.findAll(pageable);

        return PageResponseDto.from(logsPage.map(a -> new AuditLogDto(
                a.getId(),
                a.getActorId(),
                a.getActorEmail(),
                a.getSeverity(),
                a.getEventType(),
                a.getEntityName(),
                a.getEntityId(),
                a.getIpAddress(),
                a.getMetadata(),
                a.getTimestamp()
        )));
    }

    @Transactional(readOnly = true)
    public String exportImmutableCsv() {
        Pageable exportLimit = PageRequest.of(0, MAX_CSV_EXPORT_ROWS,
                Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id")));
        List<AuditLog> logs = auditLogRepository.findAll(exportLimit).getContent();
        StringBuilder sb = new StringBuilder();
        sb.append("id,timestamp,severity,event_type,actor_id,actor_email,entity_name,entity_id,ip_address,metadata\n");
        for (AuditLog log : logs) {
            sb.append(csvCell(log.getId() != null ? log.getId().toString() : "")).append(',')
              .append(csvCell(log.getTimestamp() != null ? log.getTimestamp().toString() : "")).append(',')
              .append(csvCell(log.getSeverity())).append(',')
              .append(csvCell(log.getEventType())).append(',')
              .append(csvCell(log.getActorId() != null ? log.getActorId().toString() : "")).append(',')
              .append(csvCell(log.getActorEmail())).append(',')
              .append(csvCell(log.getEntityName())).append(',')
              .append(csvCell(log.getEntityId())).append(',')
              .append(csvCell(log.getIpAddress())).append(',')
              .append(csvCell(log.getMetadata()))
              .append('\n');
        }
        return sb.toString();
    }

    public String resolveClientIp(HttpServletRequest request) {
        return clientIpResolver.resolveClientIp(request);
    }

    private String serializeMetadata(Map<String, Object> metadataMap) {
        if (metadataMap == null || metadataMap.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadataMap);
        } catch (JsonProcessingException e) {
            return "{\"raw\":\"" + metadataMap.toString().replace("\"", "'") + "\"}";
        }
    }

    private String csvCell(String value) {
        return sanitizeCsvCell(value);
    }

    /**
     * Sanitizes a CSV cell against spreadsheet formula injection (=, +, -, @, tab, CR)
     * before applying RFC-4180 quoting.
     */
    public static String sanitizeCsvCell(String value) {
        if (value == null) {
            return "";
        }
        String neutralized = value;
        if (!neutralized.isEmpty()) {
            char first = neutralized.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                neutralized = "'" + neutralized;
            }
        }
        if (neutralized.contains(",") || neutralized.contains("\"") || neutralized.contains("\n") || neutralized.contains("\r")) {
            return "\"" + neutralized.replace("\"", "\"\"") + "\"";
        }
        return neutralized;
    }
}
