package za.co.capi.dispute.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI capiDisputePortalOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Cap Trans Dsp Ptl — Enterprise Banking API")
                        .version("1.0.0")
                        .description("""
                                REST API for managing retail banking transaction disputes across domestic payment rails
                                (`PAYSHAP`, `DEBIT_ORDER`, `CARD_POS`, `ONLINE_ECOMMERCE`).
                                Includes role-based access control, cross-customer IDOR auditing, Redis idempotency locking,
                                Apache Kafka domain event streaming, partner PII masking, and login fraud lockdown with OTP recovery.
                                """)
                        .contact(new Contact()
                                .name("Monaka Phillip Moyana")
                                .email("mp.moyana@gmail.com")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Spring Boot Backend API (:8080)"),
                        new Server().url("http://localhost:4200").description("Nginx Gateway Proxy (:4200)")
                ))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT access token returned by POST /api/v1/auth/login.")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .tags(List.of(
                        new Tag()
                                .name("1. Authentication, Fraud Alert Lockdown & OTP Recovery")
                                .description("JWT login, email fraud reporting, instant session revocation, and 6-digit OTP password reset."),
                        new Tag()
                                .name("2. Retail Customer Transactions & Settlement Feed")
                                .description("Settled customer transactions eligible for dispute submission."),
                        new Tag()
                                .name("3. Transaction Disputes, Historic View, Evidence & State Machine")
                                .description("Dispute submission, SHA-256 attachment management, investigation workflow, escalation, partner delegation, and linked case re-opening."),
                        new Tag()
                                .name("4. Supervisor Administration, Immutable Audit CSV & Productivity")
                                .description("Admin endpoints for security audit logs, CSV export, staff turnaround reporting, Kafka telemetry, and user unlocks."),
                        new Tag()
                                .name("5. Omnichannel Intake Simulator (SMS, WhatsApp & Voice Call)")
                                .description("Simulates real-world banking dispute intake from inbound SMS/WhatsApp webhooks and Call Centre / IVR phone calls (ATM, filling station, or suspicious activity alerts).")
                ));
    }
}
