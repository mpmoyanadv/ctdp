package za.co.capi.dispute.application.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.FraudAlertToken;
import za.co.capi.dispute.domain.model.PasswordResetOtp;
import za.co.capi.dispute.domain.model.UserRole;
import za.co.capi.dispute.domain.repository.AppUserRepository;
import za.co.capi.dispute.domain.repository.FraudAlertTokenRepository;
import za.co.capi.dispute.domain.repository.PasswordResetOtpRepository;
import za.co.capi.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capi.dispute.infrastructure.security.JwtTokenProvider;
import za.co.capi.dispute.presentation.dto.ApiDtos.*;
import za.co.capi.dispute.presentation.exception.ApiException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppUserRepository appUserRepository;
    private final FraudAlertTokenRepository fraudAlertTokenRepository;
    private final PasswordResetOtpRepository passwordResetOtpRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final NotificationEmailService notificationEmailService;
    private final AuditService auditService;
    private final PiiMaskingService piiMaskingService;

    public AuthService(
            AppUserRepository appUserRepository,
            FraudAlertTokenRepository fraudAlertTokenRepository,
            PasswordResetOtpRepository passwordResetOtpRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            NotificationEmailService notificationEmailService,
            AuditService auditService,
            PiiMaskingService piiMaskingService) {
        this.appUserRepository = appUserRepository;
        this.fraudAlertTokenRepository = fraudAlertTokenRepository;
        this.passwordResetOtpRepository = passwordResetOtpRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.notificationEmailService = notificationEmailService;
        this.auditService = auditService;
        this.piiMaskingService = piiMaskingService;
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress) {
        AppUser user = appUserRepository.findByEmailIgnoreCase(request.email().trim())
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password."));

        if (user.isLocked()) {
            auditService.logEvent(
                    user.getId(),
                    user.getEmail(),
                    "WARN",
                    "LOGIN_BLOCKED_LOCKED",
                    "AppUser",
                    user.getId().toString(),
                    ipAddress,
                    Map.of("reason", user.getLockedReason() != null ? user.getLockedReason() : "Account locked")
            );
            throw ApiException.accountLocked(
                    "Account Locked: Security lockdown is active on this profile. Please verify your 6-digit OTP code via Reset Password to restore access."
            );
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditService.logEvent(
                    user.getId(),
                    user.getEmail(),
                    "WARN",
                    "LOGIN_FAILED_BAD_CREDENTIALS",
                    "AppUser",
                    user.getId().toString(),
                    ipAddress,
                    Map.of("email", user.getEmail())
            );
            throw ApiException.unauthorized("Invalid email or password.");
        }

        String jwt = jwtTokenProvider.generateToken(user);

        auditService.logEvent(
                user.getId(),
                user.getEmail(),
                "INFO",
                "LOGIN_SUCCESS",
                "AppUser",
                user.getId().toString(),
                ipAddress,
                Map.of("role", user.getRole().name(), "domain", user.getUserDomain().name())
        );

        if (user.getRole() == UserRole.CUSTOMER) {
            FraudAlertToken alertToken = new FraudAlertToken();
            alertToken.setToken(UUID.randomUUID() + "-" + UUID.randomUUID());
            alertToken.setUserId(user.getId());
            alertToken.setUsed(false);
            alertToken.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
            fraudAlertTokenRepository.save(alertToken);

            notificationEmailService.sendCustomerLoginAlert(
                    user.getEmail(),
                    user.getFullName(),
                    alertToken.getToken(),
                    ipAddress
            );
        }

        return new AuthResponse(
                jwt,
                "Bearer",
                jwtTokenProvider.getExpirationSeconds(),
                toUserProfile(user)
        );
    }

    @Transactional(readOnly = true)
    public FraudReportPreviewResponse previewFraudReportToken(String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.badRequest("Fraud report token is required.");
        }

        FraudAlertToken alertToken = fraudAlertTokenRepository.findByToken(token.trim())
                .orElseThrow(() -> ApiException.badRequest("Invalid or unknown fraud report token."));

        if (alertToken.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("Fraud report token has expired.");
        }
        if (alertToken.isUsed()) {
            throw ApiException.badRequest("Fraud report token has already been used.");
        }

        AppUser user = appUserRepository.findById(alertToken.getUserId())
                .orElseThrow(() -> ApiException.notFound("User account not found."));

        return new FraudReportPreviewResponse(
                true,
                piiMaskingService.maskEmail(user.getEmail()),
                "Please confirm that you want to lock your account and terminate all active sessions."
        );
    }

    @Transactional
    public FraudReportResponse reportFraudAndLockAccount(String token, String ipAddress) {
        if (token == null || token.isBlank()) {
            throw ApiException.badRequest("Fraud report token is required.");
        }

        FraudAlertToken alertToken = fraudAlertTokenRepository.findByToken(token.trim())
                .orElseThrow(() -> ApiException.badRequest("Invalid or unknown fraud report token."));

        if (alertToken.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("Fraud report token has expired.");
        }

        AppUser user = appUserRepository.findById(alertToken.getUserId())
                .orElseThrow(() -> ApiException.notFound("User account not found."));

        if (!alertToken.isUsed()) {
            alertToken.setUsed(true);
            fraudAlertTokenRepository.save(alertToken);

            user.setLocked(true);
            user.setLockedReason("Fraud reported via login security alert link");
            user.incrementTokenVersion(); // Terminates all active JWT sessions immediately
            appUserRepository.save(user);

            String otpCode = String.format("%06d", 100000 + SECURE_RANDOM.nextInt(900000));
            PasswordResetOtp otp = new PasswordResetOtp();
            otp.setUserId(user.getId());
            otp.setEmail(user.getEmail());
            otp.setOtpCode(otpCode);
            otp.setUsed(false);
            otp.setExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));
            passwordResetOtpRepository.save(otp);

            auditService.logEvent(
                    user.getId(),
                    user.getEmail(),
                    "CRITICAL",
                    "ACCOUNT_LOCKED_FRAUD_REPORTED",
                    "AppUser",
                    user.getId().toString(),
                    ipAddress,
                    Map.of(
                            "email", user.getEmail(),
                            "sessionsTerminated", true,
                            "verificationOtpDispatched", true
                    )
            );

            notificationEmailService.sendAccountLockedOtpEmail(user.getEmail(), user.getFullName(), otpCode);
        }

        return new FraudReportResponse(
                true,
                user.getEmail(),
                "Account Locked: All active sessions have been terminated and a 6-digit verification code has been emailed to " + user.getEmail() + "."
        );
    }

    @Transactional
    public UserProfileDto verifyOtpAndResetPassword(ResetPasswordVerifyRequest request, String ipAddress) {
        String normalizedEmail = request.email().trim();
        PasswordResetOtp otp = passwordResetOtpRepository
                .findTopByEmailIgnoreCaseAndOtpCodeAndUsedFalseOrderByCreatedAtDesc(normalizedEmail, request.otpCode().trim())
                .orElseThrow(() -> ApiException.badRequest("Invalid or already used 6-digit verification code."));

        if (otp.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("Verification code has expired.");
        }

        AppUser user = appUserRepository.findById(otp.getUserId())
                .orElseThrow(() -> ApiException.notFound("User not found."));

        otp.setUsed(true);
        passwordResetOtpRepository.save(otp);

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setLocked(false);
        user.setLockedReason(null);
        user.incrementTokenVersion();
        appUserRepository.save(user);

        auditService.logEvent(
                user.getId(),
                user.getEmail(),
                "INFO",
                "ACCOUNT_UNLOCKED_PASSWORD_RESET",
                "AppUser",
                user.getId().toString(),
                ipAddress,
                Map.of("email", user.getEmail(), "unlockedViaOtp", true)
        );

        return toUserProfile(user);
    }

    @Transactional
    public UserProfileDto adminUnlockAccount(UUID userId, AppUserPrincipal admin, String ipAddress) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found."));
        user.setLocked(false);
        user.setLockedReason(null);
        user.incrementTokenVersion();
        appUserRepository.save(user);

        auditService.logEvent(
                admin.getId(),
                admin.getEmail(),
                "INFO",
                "ADMIN_UNLOCKED_USER_ACCOUNT",
                "AppUser",
                user.getId().toString(),
                ipAddress,
                Map.of("unlockedUserEmail", user.getEmail())
        );

        return toUserProfile(user);
    }

    @Transactional(readOnly = true)
    public List<UserProfileDto> listAllUsers() {
        return appUserRepository.findAll().stream()
                .map(this::toUserProfile)
                .toList();
    }

    public UserProfileDto toUserProfile(AppUser user) {
        return new UserProfileDto(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getUserDomain(),
                user.getPartnerInstitution(),
                user.isLocked(),
                user.getLockedReason()
        );
    }
}

