package za.co.capi.dispute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import za.co.capi.dispute.application.service.AuditService;
import za.co.capi.dispute.domain.model.AuditLog;
import za.co.capi.dispute.domain.repository.AuditLogRepository;
import za.co.capi.dispute.infrastructure.messaging.KafkaEventService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditCsvSanitizationTest {

    @Test
    void sanitizesFormulaPrefixesAndRfc4180Characters() {
        assertThat(AuditService.sanitizeCsvCell(null)).isEmpty();
        assertThat(AuditService.sanitizeCsvCell("=CMD('calc')")).isEqualTo("'=CMD('calc')");
        assertThat(AuditService.sanitizeCsvCell("+1+1")).isEqualTo("'+1+1");
        assertThat(AuditService.sanitizeCsvCell("-1+1")).isEqualTo("'-1+1");
        assertThat(AuditService.sanitizeCsvCell("@SUM(A1:A2)")).isEqualTo("'@SUM(A1:A2)");
        assertThat(AuditService.sanitizeCsvCell("\t=1+1")).isEqualTo("'\t=1+1");
        assertThat(AuditService.sanitizeCsvCell("\r=1+1")).isEqualTo("\"'\r=1+1\"");
        assertThat(AuditService.sanitizeCsvCell("=1+1,2")).isEqualTo("\"'=1+1,2\"");
        assertThat(AuditService.sanitizeCsvCell("quote \"here\"")).isEqualTo("\"quote \"\"here\"\"\"");
        assertThat(AuditService.sanitizeCsvCell("line1\nline2")).isEqualTo("\"line1\nline2\"");
    }

    @Test
    void exportImmutableCsvNeutralizesFormulaInjectionAcrossAuditColumns() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        KafkaEventService kafka = new KafkaEventService(null, new ObjectMapper(), false);
        AuditService service = new AuditService(repo, new ObjectMapper(), kafka);

        AuditLog entry = new AuditLog();
        entry.setId(42L);
        entry.setTimestamp(Instant.parse("2026-11-14T12:00:00Z"));
        entry.setSeverity("HIGH");
        entry.setEventType("=HYPERLINK(\"http://evil.test\",\"click\")");
        entry.setActorId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        entry.setActorEmail("@attacker@capi.test");
        entry.setEntityName("Dispute");
        entry.setEntityId("+12345");
        entry.setIpAddress("-127.0.0.1");
        entry.setMetadata("{\"note\":\"line1\nline2,comma\"}");

        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entry)));

        String csv = service.exportImmutableCsv();
        String[] lines = csv.split("\n", 2);
        assertThat(lines[0]).isEqualTo("id,timestamp,severity,event_type,actor_id,actor_email,entity_name,entity_id,ip_address,metadata");
        assertThat(lines[1]).contains("\"'=HYPERLINK(\"\"http://evil.test\"\",\"\"click\"\")\"");
        assertThat(lines[1]).contains("'@attacker@capi.test");
        assertThat(lines[1]).contains("'+12345");
        assertThat(lines[1]).contains("'-127.0.0.1");
        assertThat(lines[1]).contains("\"{\"\"note\"\":\"\"line1\nline2,comma\"\"}\"");
    }
}
