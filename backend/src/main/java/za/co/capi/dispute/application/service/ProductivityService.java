package za.co.capi.dispute.application.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.Dispute;
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
        List<AppUser> internalUsers = userRepository.findByRoleInOrderByEmailAsc(List.of(UserRole.STAFF, UserRole.ADMIN));
        Map<UUID, StaffProductivityAggregateProjection> statsByStaff = sessionRepository.aggregateByStaff().stream()
                .collect(Collectors.toMap(StaffProductivityAggregateProjection::getStaffId, agg -> agg));

        List<StaffWorkSession> recentSessions = sessionRepository
                .findAllByOrderByStartedAtDesc(PageRequest.of(0, 50))
                .getContent();

        Set<UUID> recentStaffIds = new HashSet<>();
        Set<UUID> recentDisputeIds = new HashSet<>();
        for (StaffWorkSession session : recentSessions) {
            recentStaffIds.add(session.getStaffId());
            recentDisputeIds.add(session.getDisputeId());
        }

        Map<UUID, AppUser> userById = recentStaffIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(recentStaffIds).stream()
                        .collect(Collectors.toMap(AppUser::getId, u -> u));
        Map<UUID, String> disputeRefById = recentDisputeIds.isEmpty()
                ? Map.of()
                : disputeRepository.findAllById(recentDisputeIds).stream()
                        .collect(Collectors.toMap(Dispute::getId, Dispute::getDisputeReference));

        List<StaffProductivityRowDto> rows = new ArrayList<>();
        for (AppUser staff : internalUsers) {
            StaffProductivityAggregateProjection agg = statsByStaff.get(staff.getId());
            long completedCount = agg != null && agg.getCompletedSessions() != null ? agg.getCompletedSessions() : 0L;
            long activeCount = agg != null && agg.getActiveSessions() != null ? agg.getActiveSessions() : 0L;
            long totalDuration = agg != null && agg.getTotalDurationSeconds() != null ? agg.getTotalDurationSeconds() : 0L;
            long distinctDisputes = agg != null && agg.getDistinctDisputesHandled() != null ? agg.getDistinctDisputesHandled() : 0L;
            double avgDuration = completedCount == 0L
                    ? 0.0
                    : Math.round(((double) totalDuration / completedCount) * 100.0) / 100.0;

            rows.add(new StaffProductivityRowDto(
                    staff.getId(),
                    staff.getEmail(),
                    staff.getFullName(),
                    completedCount,
                    activeCount,
                    totalDuration,
                    avgDuration,
                    distinctDisputes
            ));
        }

        List<WorkSessionDetailDto> recentSessionDtos = recentSessions.stream()
                .map(s -> {
                    AppUser u = userById.get(s.getStaffId());
                    return new WorkSessionDetailDto(
                            s.getId(),
                            s.getDisputeId(),
                            disputeRefById.getOrDefault(s.getDisputeId(), s.getDisputeId().toString()),
                            s.getStaffId(),
                            u != null ? u.getEmail() : "unknown",
                            s.getStartedAt(),
                            s.getCompletedAt(),
                            s.getTotalDurationSeconds()
                    );
                })
                .toList();

        return new ProductivityReportDto(rows, recentSessionDtos);
    }
}
