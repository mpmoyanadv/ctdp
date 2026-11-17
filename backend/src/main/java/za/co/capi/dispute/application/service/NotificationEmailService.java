package za.co.capi.dispute.application.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;
import za.co.capi.dispute.domain.model.DisputeChannel;
import za.co.capi.dispute.infrastructure.messaging.KafkaEventService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Service
public class NotificationEmailService {

    public static final String ACCOUNT_LOCKED_OTP_SUBJECT = "Capi Security Lockdown: 6-Digit Verification Code";
    private static final Logger log = LoggerFactory.getLogger(NotificationEmailService.class);

    private final JavaMailSender mailSender;
    private final KafkaEventService kafkaEventService;
    private final PiiMaskingService piiMaskingService;
    private final ApplicationEventPublisher eventPublisher;
    private final String frontendBaseUrl;
    private final String fromAddress;

    @Autowired
    public NotificationEmailService(
            JavaMailSender mailSender,
            KafkaEventService kafkaEventService,
            PiiMaskingService piiMaskingService,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher,
            @Value("${app.frontend-base-url:http://localhost:4200}") String frontendBaseUrl,
            @Value("${app.mail.from:security-alerts@capi.test}") String fromAddress) {
        this.mailSender = mailSender;
        this.kafkaEventService = kafkaEventService;
        this.piiMaskingService = piiMaskingService;
        this.eventPublisher = eventPublisher;
        this.frontendBaseUrl = frontendBaseUrl;
        this.fromAddress = fromAddress;
    }

    public NotificationEmailService(
            JavaMailSender mailSender,
            KafkaEventService kafkaEventService,
            PiiMaskingService piiMaskingService,
            String frontendBaseUrl,
            String fromAddress) {
        this(mailSender, kafkaEventService, piiMaskingService, null, frontendBaseUrl, fromAddress);
    }

    public void sendCustomerLoginAlert(String recipientEmail, String fullName, String fraudToken, String ipAddress) {
        String maskedRecipient = piiMaskingService.maskEmail(recipientEmail);
        String safeIp = ipAddress != null ? ipAddress : "127.0.0.1";
        kafkaEventService.publishAlertNotificationEvent(
                "CUSTOMER_LOGIN_SECURITY_ALERT",
                maskedRecipient,
                Map.of("recipientEmail", maskedRecipient, "ipAddress", safeIp)
        );
        String secureLink = frontendBaseUrl + "/fraud-report?token=" + URLEncoder.encode(fraudToken, StandardCharsets.UTF_8);
        String escapedLink = escapeHtml(secureLink);
        String subject = "Capi Security Alert: New login detected";
        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden;">
                  <div style="background: #00529B; color: #ffffff; padding: 18px 24px;">
                    <h2 style="margin: 0; font-size: 18px;">Capi Bank Security Alert</h2>
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
                """.formatted(
                escapeHtml(fullName),
                escapeHtml(Instant.now().toString()),
                escapeHtml(safeIp),
                escapedLink,
                escapedLink,
                escapedLink
        );

        sendHtmlEmail("CUSTOMER_LOGIN_ALERT", recipientEmail, subject, html);
    }

    public void sendAccountLockedOtpEmail(String recipientEmail, String fullName, String otpCode) {
        String maskedRecipient = piiMaskingService.maskEmail(recipientEmail);
        kafkaEventService.publishAlertNotificationEvent(
                "ACCOUNT_LOCKED_FRAUD_OTP_DISPATCHED",
                maskedRecipient,
                Map.of("recipientEmail", maskedRecipient, "otpLength", otpCode != null ? otpCode.length() : 6)
        );
        String resetLink = frontendBaseUrl + "/reset-password-verify?email=" + URLEncoder.encode(recipientEmail, StandardCharsets.UTF_8);
        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; border: 1px solid #fecaca; border-radius: 8px; overflow: hidden;">
                  <div style="background: #991b1b; color: #ffffff; padding: 18px 24px;">
                    <h2 style="margin: 0; font-size: 18px;">Account Locked &mdash; Fraud Reported</h2>
                  </div>
                  <div style="padding: 24px; color: #1e293b;">
                    <p>Dear <strong>%s</strong>,</p>
                    <p>Your Capi profile has been placed under immediate security lockdown and all active sessions have been terminated.</p>
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
                """.formatted(
                escapeHtml(fullName),
                escapeHtml(otpCode),
                escapeHtml(resetLink)
        );

        sendHtmlEmail("ACCOUNT_LOCKED_OTP", recipientEmail, ACCOUNT_LOCKED_OTP_SUBJECT, html);
    }

    public void sendEscalationNotification(String adminEmail, String disputeReference, String escalatedByEmail) {
        String maskedAdmin = piiMaskingService.maskEmail(adminEmail);
        String maskedEscalatedBy = piiMaskingService.maskEmail(escalatedByEmail);
        kafkaEventService.publishAlertNotificationEvent(
                "SUPERVISOR_ESCALATION_ALERT",
                disputeReference,
                Map.of("adminEmail", maskedAdmin, "disputeReference", disputeReference, "escalatedBy", maskedEscalatedBy)
        );
        String subject = "Escalation Alert: Dispute " + disputeReference + " requires supervisor reassignment";
        String html = """
                <div style="font-family: Arial, sans-serif; padding: 20px;">
                  <h3>Dispute Escalated to Supervisor Queue</h3>
                  <p>Dispute Reference: <strong>%s</strong></p>
                  <p>Escalated By: %s</p>
                  <p>Status is now <strong>ESCALATED</strong> and unassigned awaiting Admin review.</p>
                </div>
                """.formatted(escapeHtml(disputeReference), escapeHtml(escalatedByEmail));
        sendHtmlEmail("SUPERVISOR_ESCALATION_ALERT", adminEmail, subject, html);
    }

    public void sendOmnichannelDisputeConfirmation(
            String recipientEmail,
            String fullName,
            DisputeChannel channel,
            String disputeReference,
            String merchantName,
            String amount,
            String summaryText) {

        String maskedRecipient = piiMaskingService.maskEmail(recipientEmail);
        kafkaEventService.publishAlertNotificationEvent(
                "OMNICHANNEL_DISPUTE_CONFIRMATION_" + channel.name(),
                disputeReference,
                Map.of(
                        "recipientEmail", maskedRecipient,
                        "channel", channel.name(),
                        "disputeReference", disputeReference,
                        "merchantName", merchantName,
                        "amount", amount
                )
        );
        String subject = "Capi Bank (" + channel.name() + "): Dispute " + disputeReference + " Logged";
        String escapedSummary = escapeHtml(summaryText).replace("\n", "<br/>");
        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; border: 1px solid #cbd5e1; border-radius: 8px; overflow: hidden;">
                  <div style="background: #00529B; color: #ffffff; padding: 16px 22px;">
                    <h2 style="margin: 0; font-size: 17px;">%s Dispute Confirmation &mdash; %s</h2>
                  </div>
                  <div style="padding: 20px; color: #1e293b;">
                    <p>Dear <strong>%s</strong>,</p>
                    <p>Your transaction dispute submitted via <strong>%s</strong> has been logged:</p>
                    <ul>
                      <li><strong>Dispute Reference:</strong> %s</li>
                      <li><strong>Merchant / Location:</strong> %s</li>
                      <li><strong>Amount:</strong> R %s</li>
                    </ul>
                    <p style="background: #f8fafc; border-left: 4px solid #00529B; padding: 10px 14px; font-size: 13px;">%s</p>
                  </div>
                </div>
                """.formatted(
                escapeHtml(channel.name()),
                escapeHtml(disputeReference),
                escapeHtml(fullName),
                escapeHtml(channel.name()),
                escapeHtml(disputeReference),
                escapeHtml(merchantName),
                escapeHtml(amount),
                escapedSummary
        );
        sendHtmlEmail("OMNICHANNEL_DISPUTE_CONFIRMATION", recipientEmail, subject, html);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAfterCommitEmail(PendingEmailDispatchEvent event) {
        dispatchEmailInternal(event.templateName(), event.to(), event.subject(), event.htmlBody());
    }

    private void sendHtmlEmail(String templateName, String to, String subject, String htmlBody) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new PendingEmailDispatchEvent(templateName, to, subject, htmlBody));
        } else {
            dispatchEmailInternal(templateName, to, subject, htmlBody);
        }
    }

    private void dispatchEmailInternal(String templateName, String to, String subject, String htmlBody) {
        String maskedRecipient = piiMaskingService.maskEmail(to);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Dispatched email template '{}' to {}", templateName, maskedRecipient);
        } catch (Exception ex) {
            log.warn("Unable to dispatch email template '{}' to {}: {}", templateName, maskedRecipient, ex.getMessage());
        }
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }

    public record PendingEmailDispatchEvent(
            String templateName,
            String to,
            String subject,
            String htmlBody
    ) {}
}
