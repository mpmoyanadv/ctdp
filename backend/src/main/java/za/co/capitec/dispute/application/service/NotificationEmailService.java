package za.co.capitec.dispute.application.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import za.co.capitec.dispute.infrastructure.messaging.KafkaEventService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Service
public class NotificationEmailService {

    private static final Logger log = LoggerFactory.getLogger(NotificationEmailService.class);

    private final JavaMailSender mailSender;
    private final KafkaEventService kafkaEventService;
    private final String frontendBaseUrl;
    private final String fromAddress;

    public NotificationEmailService(
            JavaMailSender mailSender,
            KafkaEventService kafkaEventService,
            @Value("${app.frontend-base-url:http://localhost:4200}") String frontendBaseUrl,
            @Value("${app.mail.from:security-alerts@capitec.test}") String fromAddress) {
        this.mailSender = mailSender;
        this.kafkaEventService = kafkaEventService;
        this.frontendBaseUrl = frontendBaseUrl;
        this.fromAddress = fromAddress;
    }

    public void sendCustomerLoginAlert(String recipientEmail, String fullName, String fraudToken, String ipAddress) {
        kafkaEventService.publishAlertNotificationEvent(
                "CUSTOMER_LOGIN_SECURITY_ALERT",
                recipientEmail,
                Map.of("recipientEmail", recipientEmail, "ipAddress", ipAddress != null ? ipAddress : "127.0.0.1")
        );
        String secureLink = frontendBaseUrl + "/fraud-report?token=" + URLEncoder.encode(fraudToken, StandardCharsets.UTF_8);
        String subject = "Capitec Security Alert: New login detected";
        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden;">
                  <div style="background: #00529B; color: #ffffff; padding: 18px 24px;">
                    <h2 style="margin: 0; font-size: 18px;">Capitec Bank Security Alert</h2>
                  </div>
                  <div style="padding: 24px; color: #1e293b;">
                    <p>Dear <strong>%s</strong>,</p>
                    <p><strong>New login detected. If not you, click here to secure account.</strong></p>
                    <p style="font-size: 13px; color: #475569;">Time: %s &bull; IP Address: %s</p>
                    <div style="margin: 26px 0;">
                      <a id="fraud-lock-link" href="%s"
                         style="display: inline-block; background: #dc2626; color: #ffffff; text-decoration: none; font-weight: bold; padding: 12px 22px; border-radius: 6px;">
                        It Wasn't Me / Secure Account
                      </a>
                    </div>
                    <p style="font-size: 12px; color: #64748b;">Direct link: <a href="%s">%s</a></p>
                  </div>
                </div>
                """.formatted(fullName, Instant.now().toString(), ipAddress, secureLink, secureLink, secureLink);

        sendHtmlEmail(recipientEmail, subject, html);
    }

    public void sendAccountLockedOtpEmail(String recipientEmail, String fullName, String otpCode) {
        kafkaEventService.publishAlertNotificationEvent(
                "ACCOUNT_LOCKED_FRAUD_OTP_DISPATCHED",
                recipientEmail,
                Map.of("recipientEmail", recipientEmail, "otpLength", otpCode != null ? otpCode.length() : 6)
        );
        String resetLink = frontendBaseUrl + "/reset-password-verify?email=" + URLEncoder.encode(recipientEmail, StandardCharsets.UTF_8);
        String subject = "Capitec Security Lockdown: 6-Digit Verification Code (" + otpCode + ")";
        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; border: 1px solid #fecaca; border-radius: 8px; overflow: hidden;">
                  <div style="background: #991b1b; color: #ffffff; padding: 18px 24px;">
                    <h2 style="margin: 0; font-size: 18px;">Account Locked &mdash; Fraud Reported</h2>
                  </div>
                  <div style="padding: 24px; color: #1e293b;">
                    <p>Dear <strong>%s</strong>,</p>
                    <p>Your Capitec profile has been placed under immediate security lockdown and all active sessions have been terminated.</p>
                    <p>Use the 6-digit verification code below to verify your identity, set a new password, and unlock your account:</p>
                    <div style="background: #f8fafc; border: 2px dashed #00529B; padding: 16px; text-align: center; margin: 20px 0; border-radius: 6px;">
                      <span id="otp-code" style="font-size: 28px; font-weight: bold; letter-spacing: 6px; color: #00529B;">%s</span>
                    </div>
                    <div style="margin: 22px 0;">
                      <a id="reset-verify-link" href="%s"
                         style="display: inline-block; background: #00529B; color: #ffffff; text-decoration: none; font-weight: bold; padding: 12px 22px; border-radius: 6px;">
                        Verify Code &amp; Unlock Account
                      </a>
                    </div>
                  </div>
                </div>
                """.formatted(fullName, otpCode, resetLink);

        sendHtmlEmail(recipientEmail, subject, html);
    }

    public void sendEscalationNotification(String adminEmail, String disputeReference, String escalatedByEmail) {
        kafkaEventService.publishAlertNotificationEvent(
                "SUPERVISOR_ESCALATION_ALERT",
                disputeReference,
                Map.of("adminEmail", adminEmail, "disputeReference", disputeReference, "escalatedBy", escalatedByEmail)
        );
        String subject = "Escalation Alert: Dispute " + disputeReference + " requires supervisor reassignment";
        String html = """
                <div style="font-family: Arial, sans-serif; padding: 20px;">
                  <h3>Dispute Escalated to Supervisor Queue</h3>
                  <p>Dispute Reference: <strong>%s</strong></p>
                  <p>Escalated By: %s</p>
                  <p>Status is now <strong>ESCALATED</strong> and unassigned awaiting Admin review.</p>
                </div>
                """.formatted(disputeReference, escalatedByEmail);
        sendHtmlEmail(adminEmail, subject, html);
    }

    private void sendHtmlEmail(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Dispatched email '{}' to {}", subject, to);
        } catch (Exception ex) {
            log.warn("Unable to dispatch email '{}' to {}: {}", subject, to, ex.getMessage());
        }
    }
}

