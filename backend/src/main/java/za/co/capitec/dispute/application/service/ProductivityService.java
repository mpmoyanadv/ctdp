package za.co.capitec.dispute.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.capitec.dispute.domain.model.AppUser;
import za.co.capitec.dispute.domain.model.Dispute;
import za.co.capitec.dispute.domain.model.StaffWorkSession;
import za.co.capitec.dispute.domain.model.UserRole;
import za.co.capitec.dispute.domain.repository.AppUserRepository;
import za.co.capitec.dispute.domain.repository.DisputeRepository;
import za.co.capitec.dispute.domain.repository.StaffWorkSessionRepository;
import za.co.capitec.dispute.presentation.dto.ApiDtos.ProductivityReportDto;
import za.co.capitec.dispute.presentation.dto.ApiDtos.StaffProductivityRowDto;
import za.co.capitec.dispute.presentation.dto.ApiDtos.WorkSessionDetailDto;

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
        List<StaffWorkSession> allSessions = sessionRepository.findAllByOrderByStartedAtDesc();

        Map<UUID, AppUser> userById = userRepository.findAll().stream()
                .collect(Collectors.toMap(AppUser::getId, u -> u));
        Map<UUID, String> disputeRefById = disputeRepository.findAll().stream()
                .collect(Collectors.toMap(Dispute::getId, Dispute::getDisputeReference));

        Map<UUID, List<StaffWorkSession>> sessionsByStaff = allSessions.stream()
                .collect(Collectors.groupingBy(StaffWorkSession::getStaffId));

        List<StaffProductivityRowDto> rows = new ArrayList<>();
        for (AppUser staff : internalUsers) {
            List<StaffWorkSession> staffSessions = sessionsByStaff.getOrDefault(staff.getId(), List.of());
            List<StaffWorkSession> completed = staffSessions.stream()
                    .filter(s -> s.getCompletedAt() != null && s.getTotalDurationSeconds() != null)
                    .toList();
            long activeCount = staffSessions.stream()
                    .filter(s -> s.getCompletedAt() == null)
                    .count();
            long totalDuration = completed.stream()
                    .mapToLong(StaffWorkSession::getTotalDurationSeconds)
                    .sum();
            double avgDuration = completed.isEmpty()
                    ? 0.0
                    : Math.round(((double) totalDuration / completed.size()) * 100.0) / 100.0;
            long distinctDisputes = staffSessions.stream()
                    .map(StaffWorkSession::getDisputeId)
                    .distinct()
                    .count();

            rows.add(new StaffProductivityRowDto(
                    staff.getId(),
                    staff.getEmail(),
                    staff.getFullName(),
                    completed.size(),
                    activeCount,
                    totalDuration,
                    avgDuration,
                    distinctDisputes
            ));
        }

        List<WorkSessionDetailDto> recentSessionDtos = allSessions.stream()
                .limit(50)
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

