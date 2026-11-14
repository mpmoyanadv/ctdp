package za.co.capi.dispute.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.capi.dispute.application.service.AuditService;
import za.co.capi.dispute.application.service.AuthService;
import za.co.capi.dispute.presentation.dto.ApiDtos.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "1. Authentication, Fraud Alert Lockdown & OTP Recovery")
@SecurityRequirements
public class AuthController {

    private final AuthService authService;
    private final String frontendBaseUrl;

    public AuthController(
            AuthService authService,
            @Value("${app.frontend-base-url:http://localhost:4200}") String frontendBaseUrl) {
        this.authService = authService;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Operation(
            summary = "Authenticate user and issue stateless JWT access token",
            description = """
                    Authenticates credentials against BCrypt password hashes for `CUSTOMER`, `STAFF`, `AFFILIATE`, and `ADMIN` actors.
                    - **Fraud Lockdown Enforcement:** Rejects login with `423 Locked` if `is_locked = true`.
                    - **Automated Customer Login Alert:** When a `CUSTOMER` logs in, dispatches an asynchronous security notification email via MailHog containing a single-use fraud-report token link (`POST /api/v1/auth/report-fraud?token=...`).
                    - **Audit Trail:** Records `LOGIN_SUCCESS`, `LOGIN_FAILED`, or `LOGIN_BLOCKED_ACCOUNT_LOCKED` with client IP.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated successfully; JWT access token issued"),
            @ApiResponse(responseCode = "401", description = "Invalid email or password"),
            @ApiResponse(responseCode = "423", description = "Account is locked due to reported fraud; requires 6-digit OTP verification")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.ok(authService.login(request, ip));
    }

    @Operation(
            summary = "Report unauthorized login and trigger instant account lockdown + 6-digit OTP",
            description = """
                    Executes emergency account lockdown when a customer clicks *"It Wasn't Me / Secure Account"* in their login security email:
                    1. Sets `is_locked = true` on the customer's `AppUser` record.
                    2. Increments `tokenVersion`, immediately invalidating all previously issued JWTs across all active sessions/devices.
                    3. Writes a `CRITICAL` immutable audit log entry (`ACCOUNT_LOCKED_FRAUD_REPORTED`).
                    4. Generates a time-bound **6-digit One-Time PIN (OTP)** and emails it via MailHog so the legitimate customer can recover access at `/api/v1/auth/reset-password-verify`.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account locked, active sessions revoked, and 6-digit OTP dispatched"),
            @ApiResponse(responseCode = "400", description = "Invalid, expired, or already-consumed fraud alert token")
    })
    @PostMapping("/report-fraud")
    public ResponseEntity<FraudReportResponse> reportFraudPost(
            @Parameter(description = "Single-use cryptographic fraud token from the login alert email", required = true)
            @RequestParam("token") String token,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.ok(authService.reportFraudAndLockAccount(token, ip));
    }

    @Operation(
            summary = "Validate fraud alert token without locking the account",
            description = "Read-only check used by the frontend confirmation screen before the user explicitly confirms emergency account lockdown."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fraud alert token is valid and awaiting explicit confirmation"),
            @ApiResponse(responseCode = "400", description = "Invalid, expired, or already-used fraud alert token")
    })
    @GetMapping("/report-fraud/preview")
    public ResponseEntity<FraudReportPreviewResponse> previewFraudReport(
            @Parameter(description = "Single-use cryptographic fraud token from the login alert email", required = true)
            @RequestParam("token") String token) {
        return ResponseEntity.ok(authService.previewFraudReportToken(token));
    }

    @Operation(
            summary = "Browser GET redirect handler for email fraud lockdown link (read-only)",
            description = "Validates the fraud token without locking the account and redirects (`302 Found`) to the Angular confirmation screen where an explicit POST action is required. Safe against email security scanners."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "Redirecting browser to frontend fraud confirmation page (account not yet locked)"),
            @ApiResponse(responseCode = "400", description = "Invalid, expired, or already-used fraud alert token")
    })
    @GetMapping("/report-fraud")
    public ResponseEntity<Void> reportFraudGetRedirect(
            @Parameter(description = "Single-use cryptographic fraud token from the login alert email", required = true)
            @RequestParam("token") String token) {
        authService.previewFraudReportToken(token);
        String redirectUrl = frontendBaseUrl + "/fraud-report?token="
                + URLEncoder.encode(token.trim(), StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, URI.create(redirectUrl).toString())
                .build();
    }

    @Operation(
            summary = "Verify 6-digit OTP, reset password, and unlock fraud-locked account",
            description = """
                    Verifies the 6-digit One-Time PIN (`otpCode`) sent after an emergency fraud lockdown, updates the user's BCrypt password hash, increments `tokenVersion`, clears `is_locked = false`, and records `ACCOUNT_UNLOCKED_VIA_OTP_RESET` in the immutable audit log.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OTP verified, password rotated, and account unlocked"),
            @ApiResponse(responseCode = "400", description = "Invalid or expired 6-digit OTP code")
    })
    @PostMapping("/reset-password-verify")
    public ResponseEntity<UserProfileDto> resetPasswordVerify(
            @Valid @RequestBody ResetPasswordVerifyRequest request,
            HttpServletRequest servletRequest) {
        String ip = AuditService.extractClientIp(servletRequest);
        return ResponseEntity.ok(authService.verifyOtpAndResetPassword(request, ip));
    }
}
