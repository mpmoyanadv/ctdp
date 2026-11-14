package za.co.capi.dispute.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import za.co.capi.dispute.domain.model.AuditLog;
import za.co.capi.dispute.domain.repository.AuditLogRepository;
import za.co.capi.dispute.infrastructure.messaging.KafkaEventService;
import za.co.capi.dispute.presentation.dto.ApiDtos.AuditLogDto;
import za.co.capi.dispute.presentation.dto.ApiDtos.PageResponseDto;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private static volatile List<IpAddressMatcher> configuredTrustedProxies = List.of();

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final KafkaEventService kafkaEventService;

    @Autowired
    public AuditService(
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            KafkaEventService kafkaEventService,
            @Value("${app.security.trusted-proxies:}") List<String> trustedProxies) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.kafkaEventService = kafkaEventService;
        configuredTrustedProxies = compileProxyMatchers(trustedProxies);
    }

    public AuditService(
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            KafkaEventService kafkaEventService) {
        this(auditLogRepository, objectMapper, kafkaEventService, List.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog logEvent(
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

    private static final int MAX_CSV_EXPORT_ROWS = 10_000;

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

    public static String extractClientIp(HttpServletRequest request) {
        return extractClientIpFromMatchers(request, configuredTrustedProxies);
    }

    public static String extractClientIp(HttpServletRequest request, Collection<String> trustedProxies) {
        return extractClientIpFromMatchers(request, compileProxyMatchers(trustedProxies));
    }

    private static String extractClientIpFromMatchers(
            HttpServletRequest request,
            List<IpAddressMatcher> trustedProxyMatchers) {
        if (request == null) {
            return "127.0.0.1";
        }
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "127.0.0.1";
        }
        if (isTrustedProxy(remoteAddr, trustedProxyMatchers)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].trim();
                if (!first.isBlank()) {
                    return first;
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return remoteAddr;
    }

    private static List<IpAddressMatcher> compileProxyMatchers(Collection<String> trustedProxies) {
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            return List.of();
        }
        return trustedProxies.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(IpAddressMatcher::new)
                .toList();
    }

    private static boolean isTrustedProxy(String remoteAddr, List<IpAddressMatcher> matchers) {
        for (IpAddressMatcher matcher : matchers) {
            try {
                if (matcher.matches(remoteAddr)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed remoteAddr (e.g. non-IP test stub) and treat as untrusted.
            }
        }
        return false;
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
        String safeValue = value;
        if (!safeValue.isEmpty()) {
            char first = safeValue.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                safeValue = "'" + safeValue;
            }
        }
        String escaped = safeValue.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}

