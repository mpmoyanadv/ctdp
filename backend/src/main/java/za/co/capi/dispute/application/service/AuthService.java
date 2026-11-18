package za.co.capi.dispute.application.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.capi.dispute.domain.model.AppUser;
import za.co.capi.dispute.domain.model.FraudAlertToken;
import za.co.capi.dispute.domain.model.PasswordResetOtp;
import za.co.capi.dispute.domain.model.UserDomain;
import za.co.capi.dispute.domain.model.UserRole;
import za.co.capi.dispute.domain.repository.AppUserRepository;
import za.co.capi.dispute.domain.repository.FraudAlertTokenRepository;
import za.co.capi.dispute.domain.repository.PasswordResetOtpRepository;
import za.co.capi.dispute.infrastructure.cache.LoginRateLimiterService;
import za.co.capi.dispute.infrastructure.cache.OtpRateLimiterService;
import za.co.capi.dispute.infrastructure.security.AppUserPrincipal;
import za.co.capi.dispute.infrastructure.security.JwtTokenProvider;
import za.co.capi.dispute.presentation.dto.ApiDtos.*;
import za.co.capi.dispute.presentation.exception.ApiException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    public static final int MAX_OTP_FAILED_ATTEMPTS = 5;
    public static final String GENERIC_LOGIN_ERROR = "Invalid email or password.";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppUserRepository appUserRepository;
    private final FraudAlertTokenRepository fraudAlertTokenRepository;
    private final PasswordResetOtpRepository passwordResetOtpRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final NotificationEmailService notificationEmailService;
    private final AuditService auditService;
    private final PiiMaskingService piiMaskingService;
    private final OtpRateLimiterService otpRateLimiterService;
    private final LoginRateLimiterService loginRateLimiterService;
    private final TransactionTemplate requiresNewTx;
    private final String dummyPasswordHash;
    private final Map<String, StaffRoleDefinitionDto> roleCatalog = Collections.synchronizedMap(new LinkedHashMap<>());

    public AuthService(
            AppUserRepository appUserRepository,
            FraudAlertTokenRepository fraudAlertTokenRepository,
            PasswordResetOtpRepository passwordResetOtpRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            NotificationEmailService notificationEmailService,
            AuditService auditService,
            PiiMaskingService piiMaskingService,
            OtpRateLimiterService otpRateLimiterService,
            LoginRateLimiterService loginRateLimiterService,
            PlatformTransactionManager transactionManager) {
        this.appUserRepository = appUserRepository;
        this.fraudAlertTokenRepository = fraudAlertTokenRepository;
        this.passwordResetOtpRepository = passwordResetOtpRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.notificationEmailService = notificationEmailService;
        this.auditService = auditService;
        this.piiMaskingService = piiMaskingService;
        this.otpRateLimiterService = otpRateLimiterService;
        this.loginRateLimiterService = loginRateLimiterService;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.dummyPasswordHash = passwordEncoder.encode("CapiTimingEqualizerDummyPassword!2026");
        initDefaultRoles();
    }

    private void initDefaultRoles() {
        registerDefaultRole(new StaffRoleDefinitionDto(
                "DISPUTE_INVESTIGATOR",
                "Dispute Investigator",
                UserRole.STAFF,
                UserDomain.INTERNAL_CAPI,
                "Investigates Supervisor-assigned disputes, attaches evidence, and resolves or escalates cases."
        ));
        registerDefaultRole(new StaffRoleDefinitionDto(
                "CALL_CENTRE_AGENT",
                "Call Centre KYC & Intake Agent",
                UserRole.STAFF,
                UserDomain.INTERNAL_CAPI,
                "Verifies calling customers via SA ID / Foreign Passport & credit-bureau checks and logs auto-assigned phone disputes."
        ));
        registerDefaultRole(new StaffRoleDefinitionDto(
                "FRAUD_SPECIALIST",
                "Senior Fraud & Chargeback Specialist",
                UserRole.STAFF,
                UserDomain.INTERNAL_CAPI,
                "Handles high-risk card fraud, ATM dispense errors, and cross-border remittance disputes."
        ));
        registerDefaultRole(new StaffRoleDefinitionDto(
                "SUPERVISOR_ADMIN",
                "Supervisor / System Administrator",
                UserRole.ADMIN,
                UserDomain.INTERNAL_CAPI,
                "Assigns and reassigns disputes, manages staff and roles, exports immutable audit CSVs, and reviews productivity."
        ));
        registerDefaultRole(new StaffRoleDefinitionDto(
                "PARTNER_BANKSERV_INVESTIGATOR",
                "BankservAfrica Clearing Investigator",
                UserRole.AFFILIATE,
                UserDomain.PARTNER_BANKSERV,
                "External clearing partner investigator for delegated PayShap and interbank rails (POPIA PII-masked)."
        ));
        registerDefaultRole(new StaffRoleDefinitionDto(
                "PARTNER_MERCHANT_INVESTIGATOR",
                "Merchant Acquirer Investigator",
                UserRole.AFFILIATE,
                UserDomain.PARTNER_MERCHANT,
                "External merchant acquirer investigator for POS and e-commerce chargeback disputes (POPIA PII-masked)."
        ));
    }

    private void registerDefaultRole(StaffRoleDefinitionDto roleDef) {
        roleCatalog.put(roleDef.roleCode(), roleDef);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress) {
        String normalizedEmail = request.email().trim();
        if (loginRateLimiterService.isIpRateLimited(ipAddress)) {
            auditService.logEvent(
                    null,
                    piiMaskingService.maskEmail(normalizedEmail),
                    "WARN",
                    "LOGIN_RATE_LIMITED_IP",
                    "AppUser",
                    "IP_RATE_LIMIT",
                    ipAddress,
                    Map.of("ipAddress", ipAddress != null ? ipAddress : "unknown")
            );
            throw ApiException.tooManyRequests("Too many failed login attempts from this IP address. Please wait before trying again.");
        }

        AppUser user = appUserRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        String targetHash = user != null ? user.getPasswordHash() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), targetHash);

        if (user == null) {
            loginRateLimiterService.recordFailure(normalizedEmail, ipAddress);
            auditService.logEvent(
                    null,
                    piiMaskingService.maskEmail(normalizedEmail),
                    "WARN",
                    "LOGIN_FAILED_UNKNOWN_USER",
                    "AppUser",
                    "UNKNOWN",
                    ipAddress,
                    Map.of()
            );
            throw ApiException.unauthorized(GENERIC_LOGIN_ERROR);
        }

        if (user.isLocked() || loginRateLimiterService.isAccountTemporarilyLocked(normalizedEmail)) {
            loginRateLimiterService.recordIpFailureOnly(ipAddress);
            auditService.logEvent(
                    user.getId(),
                    user.getEmail(),
                    "WARN",
                    "LOGIN_BLOCKED_LOCKED",
                    "AppUser",
                    user.getId().toString(),
                    ipAddress,
                    Map.of("reason", user.isLocked() && user.getLockedReason() != null ? user.getLockedReason() : "Account locked")
            );
            throw ApiException.unauthorized(GENERIC_LOGIN_ERROR);
        }

        if (!passwordMatches) {
            loginRateLimiterService.recordFailure(normalizedEmail, ipAddress);
            auditService.logEvent(
                    user.getId(),
                    user.getEmail(),
                    "WARN",
                    "LOGIN_FAILED_BAD_CREDENTIALS",
                    "AppUser",
                    user.getId().toString(),
                    ipAddress,
                    Map.of()
            );
            throw ApiException.unauthorized(GENERIC_LOGIN_ERROR);
        }

        loginRateLimiterService.resetAccountFailures(normalizedEmail);
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

            passwordResetOtpRepository.invalidateActiveOtpsForUser(user.getId());

            String otpCode = String.format("%06d", 100000 + SECURE_RANDOM.nextInt(900000));
            PasswordResetOtp otp = new PasswordResetOtp();
            otp.setUserId(user.getId());
            otp.setEmail(user.getEmail());
            otp.setOtpHash(passwordEncoder.encode(otpCode));
            otp.setFailedAttempts(0);
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
        if (!otpRateLimiterService.tryConsumeAttempt(normalizedEmail, ipAddress)) {
            auditService.logEvent(
                    null,
                    normalizedEmail,
                    "WARN",
                    "OTP_VERIFICATION_RATE_LIMITED",
                    "PasswordResetOtp",
                    normalizedEmail,
                    ipAddress,
                    Map.of("email", normalizedEmail)
            );
            throw ApiException.tooManyRequests("Too many OTP verification attempts. Please wait before trying again.");
        }

        PasswordResetOtp otp = passwordResetOtpRepository
                .findTopByEmailIgnoreCaseAndUsedFalseOrderByCreatedAtDesc(normalizedEmail)
                .orElseThrow(() -> ApiException.badRequest("Invalid or already used 6-digit verification code."));

        if (otp.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("Verification code has expired.");
        }

        if (otp.getFailedAttempts() >= MAX_OTP_FAILED_ATTEMPTS) {
            throw ApiException.tooManyRequests("Maximum OTP verification attempts exceeded. Please request a new code.");
        }

        if (!passwordEncoder.matches(request.otpCode().trim(), otp.getOtpHash())) {
            requiresNewTx.executeWithoutResult(status ->
                    passwordResetOtpRepository.incrementFailedAttempts(otp.getId(), MAX_OTP_FAILED_ATTEMPTS));
            int updatedAttempts = otp.getFailedAttempts() + 1;
            auditService.logEvent(
                    otp.getUserId(),
                    normalizedEmail,
                    "WARN",
                    "OTP_VERIFICATION_FAILED",
                    "PasswordResetOtp",
                    otp.getId().toString(),
                    ipAddress,
                    Map.of("failedAttempts", updatedAttempts)
            );
            if (updatedAttempts >= MAX_OTP_FAILED_ATTEMPTS) {
                throw ApiException.tooManyRequests("Maximum OTP verification attempts exceeded. Please request a new code.");
            }
            throw ApiException.badRequest("Invalid or already used 6-digit verification code.");
        }

        int consumed = passwordResetOtpRepository.consumeOtpAtomically(
                otp.getId(), Instant.now(), MAX_OTP_FAILED_ATTEMPTS);
        if (consumed == 0) {
            throw ApiException.conflict("Verification code has already been used or expired.");
        }

        AppUser user = appUserRepository.findById(otp.getUserId())
                .orElseThrow(() -> ApiException.notFound("User not found."));

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setLocked(false);
        user.setLockedReason(null);
        user.incrementTokenVersion();
        appUserRepository.save(user);
        loginRateLimiterService.resetAccountFailures(user.getEmail());

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
        loginRateLimiterService.resetAccountFailures(user.getEmail());

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

    public List<StaffRoleDefinitionDto> listStaffRoles() {
        synchronized (roleCatalog) {
            return new ArrayList<>(roleCatalog.values());
        }
    }

    public StaffRoleDefinitionDto createStaffRole(
            UpsertStaffRoleRequest request,
            AppUserPrincipal admin,
            String ipAddress) {
        validateRoleDomainCompatibility(request.baseRole(), request.defaultDomain(), true);

        String displayName = request.displayName().trim();
        String derivedCode = normalizeRoleCode(
                request.roleCode() != null && !request.roleCode().isBlank()
                        ? request.roleCode()
                        : displayName
        );
        if (roleCatalog.containsKey(derivedCode)) {
            throw ApiException.conflict("Role code " + derivedCode + " already exists.");
        }

        String description = request.description() != null && !request.description().isBlank()
                ? request.description().trim()
                : displayName + " role in " + request.defaultDomain().name() + ".";
        StaffRoleDefinitionDto created = new StaffRoleDefinitionDto(
                derivedCode,
                displayName,
                request.baseRole(),
                request.defaultDomain(),
                description
        );
        roleCatalog.put(derivedCode, created);

        auditService.logEvent(
                admin.getId(),
                admin.getEmail(),
                "INFO",
                "ADMIN_ROLE_CREATED",
                "StaffRole",
                derivedCode,
                ipAddress,
                Map.of(
                        "roleCode", derivedCode,
                        "displayName", displayName,
                        "baseRole", request.baseRole().name(),
                        "defaultDomain", request.defaultDomain().name()
                )
        );
        return created;
    }

    @Transactional
    public StaffRoleDefinitionDto updateStaffRole(
            String roleCode,
            UpsertStaffRoleRequest request,
            AppUserPrincipal admin,
            String ipAddress) {
        validateRoleDomainCompatibility(request.baseRole(), request.defaultDomain(), true);

        String normalizedCode = normalizeRoleCode(roleCode);
        StaffRoleDefinitionDto previous = roleCatalog.get(normalizedCode);
        String displayName = request.displayName().trim();
        String description = request.description() != null && !request.description().isBlank()
                ? request.description().trim()
                : (previous != null ? previous.description() : displayName + " role.");

        StaffRoleDefinitionDto updated = new StaffRoleDefinitionDto(
                normalizedCode,
                displayName,
                request.baseRole(),
                request.defaultDomain(),
                description
        );
        roleCatalog.put(normalizedCode, updated);

        if (previous != null && !previous.displayName().equalsIgnoreCase(displayName)) {
            List<AppUser> matchedUsers = appUserRepository.findAll().stream()
                    .filter(u -> u.getPartnerInstitution() != null
                            && u.getPartnerInstitution().trim().equalsIgnoreCase(previous.displayName()))
                    .toList();
            for (AppUser matched : matchedUsers) {
                matched.setPartnerInstitution(displayName);
                appUserRepository.save(matched);
            }
        }

        auditService.logEvent(
                admin.getId(),
                admin.getEmail(),
                "INFO",
                "ADMIN_ROLE_UPDATED",
                "StaffRole",
                normalizedCode,
                ipAddress,
                Map.of(
                        "roleCode", normalizedCode,
                        "displayName", displayName,
                        "baseRole", request.baseRole().name(),
                        "defaultDomain", request.defaultDomain().name()
                )
        );
        return updated;
    }

    @Transactional
    public UserProfileDto adminCreateUser(
            CreateStaffUserRequest request,
            AppUserPrincipal admin,
            String ipAddress) {
        String email = request.email().trim();
        if (appUserRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw ApiException.conflict("A user with email " + email + " already exists.");
        }
        validateRoleDomainCompatibility(request.role(), request.userDomain(), false);

        String rawPassword;
        if (request.password() != null && !request.password().isBlank()) {
            rawPassword = request.password().trim();
            if (rawPassword.length() < 8) {
                throw ApiException.badRequest("Password must be at least 8 characters.");
            }
        } else {
            rawPassword = "Password123!";
        }

        AppUser user = new AppUser();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPhoneNumber(blankToNull(request.phoneNumber()));
        user.setRole(request.role());
        user.setUserDomain(request.userDomain());
        user.setPartnerInstitution(resolveRoleTitle(request.partnerInstitution(), request.role(), request.userDomain()));
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setLocked(false);

        AppUser saved = appUserRepository.save(user);

        auditService.logEvent(
                admin.getId(),
                admin.getEmail(),
                "INFO",
                "ADMIN_STAFF_USER_CREATED",
                "AppUser",
                saved.getId().toString(),
                ipAddress,
                Map.of(
                        "createdEmail", saved.getEmail(),
                        "fullName", saved.getFullName(),
                        "role", saved.getRole().name(),
                        "userDomain", saved.getUserDomain().name(),
                        "roleTitle", saved.getPartnerInstitution() != null ? saved.getPartnerInstitution() : ""
                )
        );

        return toUserProfile(saved);
    }

    @Transactional
    public UserProfileDto adminUpdateUser(
            UUID userId,
            UpdateStaffUserRequest request,
            AppUserPrincipal admin,
            String ipAddress) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found."));

        String oldEmail = user.getEmail();
        String newEmail = request.email().trim();
        if (!oldEmail.equalsIgnoreCase(newEmail) && appUserRepository.findByEmailIgnoreCase(newEmail).isPresent()) {
            throw ApiException.conflict("A user with email " + newEmail + " already exists.");
        }

        validateRoleDomainCompatibility(request.role(), request.userDomain(), false);

        boolean securityContextChanged = user.getRole() != request.role()
                || user.getUserDomain() != request.userDomain();

        user.setFullName(request.fullName().trim());
        user.setEmail(newEmail);
        user.setPhoneNumber(blankToNull(request.phoneNumber()));
        user.setRole(request.role());
        user.setUserDomain(request.userDomain());
        user.setPartnerInstitution(resolveRoleTitle(request.partnerInstitution(), request.role(), request.userDomain()));

        if (request.isLocked() != null) {
            boolean nextLocked = request.isLocked();
            if (user.isLocked() != nextLocked) {
                securityContextChanged = true;
            }
            user.setLocked(nextLocked);
            if (nextLocked) {
                String reason = blankToNull(request.lockedReason());
                user.setLockedReason(reason != null ? reason : "Administratively locked by Supervisor");
            } else {
                user.setLockedReason(null);
                loginRateLimiterService.resetAccountFailures(oldEmail);
                loginRateLimiterService.resetAccountFailures(newEmail);
            }
        }

        if (request.newPassword() != null && !request.newPassword().isBlank()) {
            String trimmedPassword = request.newPassword().trim();
            if (trimmedPassword.length() < 8) {
                throw ApiException.badRequest("Password must be at least 8 characters.");
            }
            user.setPasswordHash(passwordEncoder.encode(trimmedPassword));
            securityContextChanged = true;
        }

        if (securityContextChanged) {
            user.incrementTokenVersion();
        }

        AppUser saved = appUserRepository.save(user);

        auditService.logEvent(
                admin.getId(),
                admin.getEmail(),
                "INFO",
                "ADMIN_STAFF_USER_UPDATED",
                "AppUser",
                saved.getId().toString(),
                ipAddress,
                Map.of(
                        "updatedEmail", saved.getEmail(),
                        "fullName", saved.getFullName(),
                        "role", saved.getRole().name(),
                        "userDomain", saved.getUserDomain().name(),
                        "roleTitle", saved.getPartnerInstitution() != null ? saved.getPartnerInstitution() : "",
                        "isLocked", saved.isLocked()
                )
        );

        return toUserProfile(saved);
    }

    private void validateRoleDomainCompatibility(UserRole role, UserDomain domain, boolean staffRoleOnly) {
        if (role == null || domain == null) {
            throw ApiException.badRequest("Role and domain are required.");
        }
        if (staffRoleOnly && (role == UserRole.CUSTOMER || domain == UserDomain.CUSTOMER)) {
            throw ApiException.badRequest("Staff and partner role definitions cannot use CUSTOMER role or domain.");
        }
        if ((role == UserRole.STAFF || role == UserRole.ADMIN) && domain != UserDomain.INTERNAL_CAPI) {
            throw ApiException.badRequest(role.name() + " users and roles must belong to the INTERNAL_CAPI domain.");
        }
        if (role == UserRole.AFFILIATE && domain != UserDomain.PARTNER_BANKSERV && domain != UserDomain.PARTNER_MERCHANT) {
            throw ApiException.badRequest("AFFILIATE users and roles must belong to PARTNER_BANKSERV or PARTNER_MERCHANT.");
        }
        if (role == UserRole.CUSTOMER && domain != UserDomain.CUSTOMER) {
            throw ApiException.badRequest("CUSTOMER accounts must belong to the CUSTOMER domain.");
        }
    }

    private String resolveRoleTitle(String requestedTitle, UserRole role, UserDomain domain) {
        String trimmed = blankToNull(requestedTitle);
        if (trimmed != null) {
            return trimmed;
        }
        return switch (role) {
            case STAFF -> "Dispute Investigator";
            case ADMIN -> "Supervisor / System Administrator";
            case AFFILIATE -> domain == UserDomain.PARTNER_BANKSERV ? "BankservAfrica" : "Merchant Acquirer";
            case CUSTOMER -> null;
        };
    }

    private String normalizeRoleCode(String rawCode) {
        String normalized = rawCode == null
                ? ""
                : rawCode.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            throw ApiException.badRequest("Role code / display name must contain alphanumeric characters.");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
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
                user.getLockedReason(),
                user.getPhoneNumber()
        );
    }
}

