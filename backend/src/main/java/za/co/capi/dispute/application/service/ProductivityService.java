package za.co.capi.dispute.application.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.Dispute;
import za.co.capi.dispute.domain.model.DisputeChannel;
import za.co.capi.dispute.domain.model.DisputeStatus;
import za.co.capi.dispute.domain.model.StaffWorkSession;
import za.co.capi.dispute.domain.model.UserRole;
import za.co.capi.dispute.domain.repository.AppUserRepository;
import za.co.capi.dispute.domain.repository.DisputeRepository;
import za.co.capi.dispute.domain.repository.StaffWorkSessionRepository;
import za.co.capi.dispute.domain.repository.StaffWorkSessionRepository.StaffProductivityAggregateProjection;
import za.co.capi.dispute.presentation.dto.ApiDtos.ProductivityReportDto;
import za.co.capi.dispute.presentation.dto.ApiDtos.StaffProductivityRowDto;
import za.co.capi.dispute.presentation.dto.ApiDtos.WorkSessionDetailDto;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProductivityService {

    private final StaffWorkSessionRepository sessionRepository;
    private final AppUserRepository userRepository;
    private final DisputeRepository disputeRepository;

    public ProductivityService(
            StaffWorkSessionRepository sessionRepository,
            AppUserRepository userRepository,
            DisputeRepository disputeRepository) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.disputeRepository = disputeRepository;
    }

    @Transactional
    public StaffWorkSession startWorkSession(UUID disputeId, UUID staffId) {
        closeActiveSessionsForDispute(disputeId);
        StaffWorkSession session = new StaffWorkSession();
        session.setDisputeId(disputeId);
        session.setStaffId(staffId);
        session.setStartedAt(Instant.now());
        return sessionRepository.save(session);
    }

    @Transactional
    public void closeActiveSessionsForDispute(UUID disputeId) {
        List<StaffWorkSession> activeSessions = sessionRepository.findByDisputeIdAndCompletedAtIsNull(disputeId);
        Instant now = Instant.now();
        for (StaffWorkSession session : activeSessions) {
            session.setCompletedAt(now);
            long elapsedSeconds = Math.max(1L, Duration.between(session.getStartedAt(), now).getSeconds());
            session.setTotalDurationSeconds(elapsedSeconds);
            sessionRepository.save(session);
        }
    }

    @Transactional(readOnly = true)
    public ProductivityReportDto buildProductivityReport() {
        Instant now = Instant.now();
        List<AppUser> internalUsers = userRepository.findByRoleInOrderByEmailAsc(List.of(UserRole.STAFF, UserRole.ADMIN));
        Map<UUID, StaffProductivityAggregateProjection> statsByStaff = sessionRepository.aggregateByStaff().stream()
                .collect(Collectors.toMap(StaffProductivityAggregateProjection::getStaffId, agg -> agg));

        List<Dispute> allDisputes = disputeRepository.findAll();
        Map<UUID, Dispute> disputeById = allDisputes.stream()
                .collect(Collectors.toMap(Dispute::getId, d -> d));

        List<StaffWorkSession> recentSessions = sessionRepository
                .findAllByOrderByStartedAtDesc(PageRequest.of(0, 50))
                .getContent();

        Set<UUID> staffIdsToResolve = new HashSet<>();
        Map<UUID, Long> activeElapsedByStaff = new HashMap<>();
        Map<UUID, Set<UUID>> sessionDisputesByStaff = new HashMap<>();
        for (StaffWorkSession session : recentSessions) {
            staffIdsToResolve.add(session.getStaffId());
            sessionDisputesByStaff
                    .computeIfAbsent(session.getStaffId(), k -> new HashSet<>())
                    .add(session.getDisputeId());
            if (session.getCompletedAt() == null) {
                long activeSec = computeElapsedSeconds(session, now);
                activeElapsedByStaff.merge(session.getStaffId(), activeSec, Long::sum);
            }
        }

        Map<UUID, AppUser> userById = new HashMap<>();
        for (AppUser internalUser : internalUsers) {
            userById.put(internalUser.getId(), internalUser);
        }
        Set<UUID> missingUserIds = staffIdsToResolve.stream()
                .filter(id -> !userById.containsKey(id))
                .collect(Collectors.toSet());
        if (!missingUserIds.isEmpty()) {
            userRepository.findAllById(missingUserIds).forEach(u -> userById.put(u.getId(), u));
        }

        List<StaffProductivityRowDto> rows = new ArrayList<>();
        for (AppUser staff : internalUsers) {
            UUID staffId = staff.getId();
            StaffProductivityAggregateProjection agg = statsByStaff.get(staffId);
            long completedCount = agg != null && agg.getCompletedSessions() != null ? agg.getCompletedSessions() : 0L;
            long activeCount = agg != null && agg.getActiveSessions() != null ? agg.getActiveSessions() : 0L;
            long completedDuration = agg != null && agg.getTotalDurationSeconds() != null ? agg.getTotalDurationSeconds() : 0L;
            long activeDuration = activeElapsedByStaff.getOrDefault(staffId, 0L);
            long totalDuration = completedDuration + activeDuration;
            long totalSessionsTracked = completedCount + activeCount;
            double avgDuration = totalSessionsTracked == 0L
                    ? 0.0
                    : Math.round(((double) totalDuration / totalSessionsTracked) * 100.0) / 100.0;

            Set<UUID> sessionDisputeIds = sessionDisputesByStaff.getOrDefault(staffId, Set.of());
            long currentlyAssigned = 0L;
            long callCentreLogged = 0L;
            long resolvedCount = 0L;
            Set<UUID> handledDisputes = new HashSet<>(sessionDisputeIds);
            for (Dispute d : allDisputes) {
                boolean assignedToStaff = staffId.equals(d.getAssignedUserId());
                boolean workedInSession = sessionDisputeIds.contains(d.getId());
                if (assignedToStaff) {
                    handledDisputes.add(d.getId());
                    if (!d.getStatus().isTerminal()) {
                        currentlyAssigned++;
                    }
                    if (d.getSourceChannel() == DisputeChannel.PHONE_CALL) {
                        callCentreLogged++;
                    }
                }
                if (d.getStatus().isTerminal() && (assignedToStaff || workedInSession)) {
                    resolvedCount++;
                }
            }
            long aggDistinct = agg != null && agg.getDistinctDisputesHandled() != null ? agg.getDistinctDisputesHandled() : 0L;
            long distinctDisputes = Math.max(handledDisputes.size(), aggDistinct);

            rows.add(new StaffProductivityRowDto(
                    staffId,
                    staff.getEmail(),
                    staff.getFullName(),
                    completedCount,
                    activeCount,
                    totalDuration,
                    avgDuration,
                    distinctDisputes,
                    staff.getRole().name(),
                    resolveRoleTitle(staff),
                    currentlyAssigned,
                    callCentreLogged,
                    resolvedCount
            ));
        }

        List<WorkSessionDetailDto> recentSessionDtos = recentSessions.stream()
                .map(s -> {
                    AppUser u = userById.get(s.getStaffId());
                    Dispute d = disputeById.get(s.getDisputeId());
                    String disputeRef = d != null ? d.getDisputeReference() : s.getDisputeId().toString();
                    String disputeStatus = d != null ? d.getStatus().name() : DisputeStatus.IN_REVIEW.name();
                    return new WorkSessionDetailDto(
                            s.getId(),
                            s.getDisputeId(),
                            disputeRef,
                            s.getStaffId(),
                            u != null ? u.getEmail() : "unknown",
                            s.getStartedAt(),
                            s.getCompletedAt(),
                            s.getTotalDurationSeconds(),
                            u != null ? u.getFullName() : "Unknown Investigator",
                            disputeStatus,
                            resolveAssignmentSource(d),
                            resolveSessionOutcome(s, d),
                            computeElapsedSeconds(s, now)
                    );
                })
                .toList();

        return new ProductivityReportDto(rows, recentSessionDtos);
    }

    private String resolveRoleTitle(AppUser staff) {
        if (staff.getPartnerInstitution() != null && !staff.getPartnerInstitution().isBlank()) {
            return staff.getPartnerInstitution().trim();
        }
        return staff.getRole() == UserRole.ADMIN
                ? "Supervisor / System Administrator"
                : "Dispute Investigator";
    }

    private String resolveAssignmentSource(Dispute dispute) {
        if (dispute != null && dispute.getSourceChannel() == DisputeChannel.PHONE_CALL) {
            return "Call Centre Intake (Auto-Assigned)";
        }
        return "Supervisor / Admin Assignment";
    }

    private String resolveSessionOutcome(StaffWorkSession session, Dispute dispute) {
        if (session.getCompletedAt() == null) {
            return "Active (In Review)";
        }
        if (dispute == null) {
            return "Completed";
        }
        if (dispute.getStatus().isTerminal()) {
            return "Completed (" + dispute.getStatus().name() + ")";
        }
        if (dispute.getStatus() == DisputeStatus.ESCALATED) {
            return "Escalated to Supervisor";
        }
        if (dispute.getStatus() == DisputeStatus.PENDING_EXTERNAL_REVIEW) {
            return "Delegated (" + dispute.getAssignedDomain().name() + ")";
        }
        return "Reassigned by Supervisor";
    }

    private long computeElapsedSeconds(StaffWorkSession session, Instant now) {
        if (session.getTotalDurationSeconds() != null) {
            return session.getTotalDurationSeconds();
        }
        return Math.max(1L, Duration.between(session.getStartedAt(), now).getSeconds());
    }
}
