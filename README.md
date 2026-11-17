# Cap Trans Dsp Ptl

Full-stack transaction dispute management application built with **Java 21**, **Spring Boot 3.3.5**, **PostgreSQL 16**, **Redis 7**, **Apache Kafka 3.8**, and **Angular 18**.

Customers can view settled account transactions across payment rails (`CARD_POS`, `ONLINE_ECOMMERCE`, `PAYSHAP`, `DEBIT_ORDER`), submit disputes with supporting evidence, filter active vs. historic disputed transactions, and re-open rejected claims with full case lineage. Internal staff, external clearing partners, and admins handle investigation, escalation, PII-masked delegation, auditing, and turnaround reporting.

---

## Architecture & Project Layout

```text
transactions_dispute_portal/
├── .github/workflows/ci.yml  # GitHub Actions CI (backend tests, Angular tests & build, Compose check)
├── docker-compose.yml        # PostgreSQL, Redis, Kafka (KRaft), MailHog, Backend, Frontend
├── scripts/smoke.sh          # End-to-end API & UI smoke verification script
├── backend/
│   ├── Dockerfile            # Multi-stage Maven build + JUnit tests -> JRE 21 Alpine
│   ├── pom.xml
│   └── src/
│       ├── main/java/za/co/capi/dispute/
│       │   ├── domain/           # JPA entities & repositories
│       │   ├── application/      # Core business services (Auth, Dispute, Audit, Storage, PII Masking, Productivity)
│       │   ├── infrastructure/   # Security (JWT, rate limiting, proxy trust), Redis idempotency lock, Kafka, OpenAPI, DataSeeder
│       │   └── presentation/     # REST controllers, DTOs, global exception handler
│       ├── main/resources/db/migration/ # Flyway migrations (common + postgresql)
│       └── test/java/za/co/capi/dispute/ # 15 JUnit 5 integration & unit test classes
└── frontend/
    ├── Dockerfile            # Multi-stage Node 22 (`npm ci`) build -> Nginx 1.27 Alpine
    ├── nginx.conf            # SPA routing + reverse proxy to backend:8080
    ├── package-lock.json     # Locked frontend dependencies
    └── src/app/
        ├── core/             # Auth service, API client, JWT interceptor, route guards + *.spec.ts unit tests
        └── features/         # Login, Fraud Report, OTP Reset, Customer, Staff, Affiliate, Admin, Dispute Detail
```

---

## Key Features

- **Customer Transactions & Historic Dispute View:** View settled transactions with inline dispute status badges and filter logged disputes by `All`, `Active`, or `Historic / Closed` (`RESOLVED`, `REFUNDED`, `REJECTED`).
- **Dispute Lifecycle & Linked Re-opening:** State machine covering `LOGGED`, `IN_REVIEW`, `PENDING_CUSTOMER_ACTION`, `PENDING_EXTERNAL_REVIEW`, `ESCALATED`, and immutable terminal states (`RESOLVED`, `REFUNDED`, `REJECTED`). Rejected disputes can be re-opened with an `original_dispute_id` lineage link.
- **Multi-File Evidence Uploads:** Supports up to 5 files per dispute (`PDF`, `PNG`, `JPG`, max 5MB each) with SHA-256 checksums stored per attachment. Uploads and notes are blocked once a dispute reaches a terminal state.
- **IDOR Prevention & Immutable Audit Trail:** Cross-customer or unauthorized cross-domain access attempts fail with `403 Forbidden` and persist a high-severity `UNAUTHORIZED_ACCESS_ATTEMPT` audit record (`REQUIRES_NEW` transaction), exportable as CSV.
- **External Partner PII Masking:** Disputes delegated to `PARTNER_BANKSERV` or `PARTNER_MERCHANT` automatically mask customer PII (`SA ID`, `phone`, `account number`, `email`, `name`) in API responses.
- **Login Fraud Lockdown & OTP Recovery:** Customer logins send an alert email (via MailHog) with an "It Wasn't Me" link that locks the account, revokes active JWTs via `tokenVersion`, and emails a 6-digit OTP to verify and reset the password.
- **Redis Idempotency Locking:** `POST /api/v1/disputes` uses atomic Redis `SET NX EX` locks (via `X-Idempotency-Key` or request payload fingerprint) to prevent duplicate submissions on retries (`409 Conflict`).
- **Apache Kafka Event Streaming:** Publishes and consumes domain events across `capi.dispute.lifecycle.events`, `capi.security.audit.events`, and `capi.notification.alert.events`.
- **Omnichannel SMS / WhatsApp / Voice Call Simulator:** Interactive simulator at `/simulator` allowing realistic dispute intake from inbound SMS/WhatsApp alerts and Call Centre voice calls (ATM cash-not-dispensed, filling station double-tap, or suspicious transaction alerts), complete with gateway auto-replies, call transcripts, and email receipts.
- **Staff Productivity Tracking:** Tracks active work duration (`StaffWorkSession`) from dispute claim to resolution, escalation, or delegation.

---

## Pre-Seeded Demo Accounts

`DataSeeder` seeds 5 users (all with password `Password123!`), 6 sample transactions, 1 active PayShap dispute (`DSP-2026-10042`), and 1 historic refunded dispute (`DSP-2026-09811`):

| Email | Role | Domain |
| :--- | :--- | :--- |
| `customer@capi.test` | `CUSTOMER` | `CUSTOMER` |
| `customer2@capi.test` | `CUSTOMER` | `CUSTOMER` |
| `staff@capi.test` | `STAFF` | `INTERNAL_CAPI` |
| `affiliate@bankserv.test` | `AFFILIATE` | `PARTNER_BANKSERV` |
| `admin@capi.test` | `ADMIN` | `INTERNAL_CAPI` |

---

## Building & Running

### Prerequisites
- Docker & Docker Compose v2+
- *(Optional for local runs)* Java 21, Maven 3.9+, Node.js 22

### Run Full Stack with Docker Compose

```bash
docker compose up -d --build
```

Check running containers:

```bash
docker compose ps
```

Services:
- **Frontend UI:** http://localhost:4200
- **Backend API:** http://localhost:8080 (`http://localhost:8080/actuator/health`)
- **Swagger UI (OpenAPI):** http://localhost:8080/swagger-ui.html (also proxied at http://localhost:4200/swagger-ui/index.html)
- **OpenAPI JSON Spec:** http://localhost:8080/v3/api-docs
- **MailHog Web UI:** http://localhost:8025
- **PostgreSQL 16:** `localhost:5432` (`capi_disputes` / `capi` / `capi_secret`)
- **Redis 7:** `localhost:6379`
- **Apache Kafka 3.8:** `localhost:9092`

To stop containers and clear database/attachment volumes:

```bash
docker compose down -v
```

### Run Locally Without Full Docker

1. Start infrastructure services:
   ```bash
   docker compose up -d postgres redis kafka mailhog
   ```
2. Start backend:
   ```bash
   cd backend
   mvn spring-boot:run
   ```
3. Start frontend:
   ```bash
   cd frontend
   npm ci --legacy-peer-deps
   npm start
   ```

---

## Testing

### Automated Backend Tests (JUnit 5 + MockMvc + H2)

The test suite runs automatically during the Docker backend image build, or locally via Maven:

```bash
cd backend
mvn clean test
```

Coverage includes:
- Full dispute lifecycle with multipart evidence uploads, magic-byte validation, and SHA-256 verification (`DisputePortalIntegrationTest`, `AttachmentSecurityTest`)
- Redis idempotency lock rejection (`409 Conflict`) on duplicate submission and transaction ownership/settlement checks (`TransactionIntegrityTest`)
- Cross-customer IDOR rejection (`403 Forbidden`), role/transition enforcement (`DisputeAuthorizationAndTransitionTest`), and `UNAUTHORIZED_ACCESS_ATTEMPT` audit logging
- Fraud alert two-step confirmation lockdown, JWT session revocation, and cryptographic 6-digit OTP password reset (`FraudRecoveryConfirmationTest`, `OtpSecurityTest`)
- Login brute-force protection: 5-failure temporary account lockout, 20-failure/5-minute per-IP `429` throttling, failure counter reset on success, and uniform `401` responses (`LoginBruteForceAndLockoutTest`)
- External affiliate PII masking (`PiiMaskingServiceTest`)
- Fail-fast JWT secret validation, profile-gated demo seeding, and CORS/proxy trust (`JwtSecretValidationTest`, `DataSeederWithDemoProfileTest`, `DataSeederWithoutDemoProfileTest`, `CorsAndProxyTrustTest`)
- Flyway schema migrations, pagination boundaries, stable sorting, CSV formula-injection sanitization, and Kafka telemetry (`FlywayMigrationAndConstraintTest`, `PaginationAndQueryOptimizationTest`, `AuditCsvSanitizationTest`, `KafkaEventServiceTest`)

### Automated Frontend Tests (Angular Karma + Jasmine)

```bash
cd frontend
npm ci --legacy-peer-deps
NG_CLI_ANALYTICS=false npm test
```

Covers `AuthService`, `PortalApiService` (`PageResponse<T>` unwrapping), `authGuard`/`roleGuard`, and `authInterceptor` in headless Chrome.

### End-to-End Smoke Verification & CI

After bringing up the Docker Compose stack (`docker compose up -d --build`), run:

```bash
./scripts/smoke.sh
```

GitHub Actions CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)) runs the backend Maven tests, frontend Karma unit tests + production build, and `docker compose config -q` on pushes and pull requests to `main`.

---

## Main API Endpoints

| Method & Path | Access | Purpose |
| :--- | :--- | :--- |
| `POST /api/v1/auth/login` | Public | Authenticate user and issue JWT (triggers customer login alert email) |
| `POST /api/v1/auth/report-fraud?token=...` | Public | Lock account, revoke active JWTs, log audit, email 6-digit OTP |
| `POST /api/v1/auth/reset-password-verify` | Public | Verify 6-digit OTP, update password, unlock account |
| `GET /api/v1/transactions` | `CUSTOMER` | List authenticated customer's transactions |
| `GET /api/v1/disputes` | Authenticated | List paginated disputes (`page`, `size`, optional `status`) scoped by role/domain |
| `POST /api/v1/disputes` | `CUSTOMER` | Create dispute (JSON or `multipart/form-data`, Redis idempotency locked) |
| `GET /api/v1/disputes/{id}` | Authenticated | Get dispute details, notes, attachments, and lineage |
| `POST /api/v1/disputes/{id}/attachments` | `CUSTOMER`, `STAFF`, `ADMIN` | Upload additional evidence files (blocked in terminal states) |
| `POST /api/v1/disputes/{id}/claim` | `STAFF`, `ADMIN` | Claim dispute (`IN_REVIEW`) and start work session timer |
| `POST /api/v1/disputes/{id}/notes` | Authenticated | Add note (can trigger `PENDING_CUSTOMER_ACTION`) |
| `POST /api/v1/disputes/{id}/escalate` | `CUSTOMER`, `STAFF`, `ADMIN` | Escalate dispute to supervisor queue |
| `POST /api/v1/disputes/{id}/delegate` | `STAFF`, `ADMIN` | Delegate dispute to `PARTNER_BANKSERV` or `PARTNER_MERCHANT` |
| `PATCH /api/v1/disputes/{id}/status` | `STAFF`, `ADMIN` | Transition dispute status |
| `POST /api/v1/disputes/{id}/reopen` | `CUSTOMER` | Re-open a `REJECTED` dispute as a linked case |
| `GET /api/v1/admin/audit-logs` | `ADMIN` | View paginated audit logs (`page`, `size`, optional `eventType`) |
| `GET /api/v1/admin/audit-logs/export` | `ADMIN` | Export audit logs as CSV (bounded to 10,000 most recent rows) |
| `GET /api/v1/admin/reports/productivity` | `ADMIN` | View investigator turnaround metrics |
| `GET /api/v1/admin/events/kafka-stream` | `ADMIN` | Inspect Kafka topics/events and Redis lock status |
| `POST /api/v1/admin/users/{id}/unlock` | `ADMIN` | Unlock a locked user account |
| `POST /api/v1/admin/disputes/{id}/assign` | `ADMIN` | Assign/reassign dispute to investigator or partner domain |
| `POST /api/v1/omnichannel/sms-webhook` | Authenticated | Simulate inbound SMS/WhatsApp dispute intake & auto-reply |
| `POST /api/v1/omnichannel/voice-call` | Authenticated | Simulate Call Centre / IVR voice call dispute intake & transcript |

## Dispute Status Transition Policy

Enforced centrally in `DisputeTransitionPolicy` and `DisputeService`:

| Current Status | Allowed Next Statuses |
| :--- | :--- |
| `LOGGED` | `IN_REVIEW`, `PENDING_CUSTOMER_ACTION`, `PENDING_EXTERNAL_REVIEW`, `ESCALATED`, `REJECTED` |
| `IN_REVIEW` | `PENDING_CUSTOMER_ACTION`, `PENDING_EXTERNAL_REVIEW`, `ESCALATED`, `RESOLVED`, `REFUNDED`, `REJECTED` |
| `PENDING_CUSTOMER_ACTION` | `IN_REVIEW`, `ESCALATED`, `RESOLVED`, `REFUNDED`, `REJECTED` |
| `PENDING_EXTERNAL_REVIEW` | `IN_REVIEW`, `ESCALATED`, `RESOLVED`, `REFUNDED`, `REJECTED` |
| `ESCALATED` | `IN_REVIEW`, `PENDING_CUSTOMER_ACTION`, `PENDING_EXTERNAL_REVIEW`, `RESOLVED`, `REFUNDED`, `REJECTED` |
| `RESOLVED`, `REFUNDED`, `REJECTED` | Terminal — no transitions permitted (`REJECTED` may be re-opened as a new linked `LOGGED` dispute by the owning `CUSTOMER`) |

- **Role boundaries:** Only `STAFF` and `ADMIN` can invoke `/status`, `/claim`, or `/delegate`. `CUSTOMER` users can escalate their own disputes (`ESCALATED`), reply to `PENDING_CUSTOMER_ACTION` notes (returning to `IN_REVIEW`), upload evidence, and re-open `REJECTED` disputes. `AFFILIATE` users can only read disputes assigned to their partner domain (with PII masked) and add non-internal notes.
- **Admin assignment validation:** `POST /api/v1/admin/disputes/{id}/assign` rejects `CUSTOMER` as a target domain and requires any `assigneeUserId` to be unlocked and domain/role compatible (`STAFF`/`ADMIN` in `INTERNAL_CAPI` or `AFFILIATE` in the matching partner domain).

## Transaction Integrity Rules

- **Transaction-backed disputes (`transactionId` supplied):**
  - Verifies the authenticated customer owns the referenced `BankTransaction` (`403 Forbidden` otherwise).
  - Requires `BankTransaction.status == SETTLED` (`409 Conflict` for `PENDING` or `REVERSED` transactions).
  - Prevents duplicate active disputes on the same `transactionId` (`409 Conflict` while a non-terminal dispute exists; `REJECTED` cases can still be re-opened).
  - Derives `merchantName`, `paymentRail`, and `amount` authoritatively from `BankTransaction`, ignoring any tampered client-supplied values.
- **Free-form / unlinked disputes (`transactionId` omitted):** Supported for omnichannel or unlinked intakes where a ledger transaction ID is not yet matched; uses client/channel-supplied merchant, rail, and amount with safe defaults.

## Attachment Security & Malware-Scanning Trade-Off

- **Signature & limit enforcement:** `AttachmentStorageService` validates magic-byte file signatures (`%PDF-`, 8-byte PNG, JPEG `FF D8 FF`), verifies signature/extension/`Content-Type` consistency, enforces a 5 MB per-file limit and max 5 attachments per dispute, blocks uploads to terminal disputes (`409 Conflict`), and registers a `TransactionSynchronization` hook to remove written files if the database transaction rolls back. Downloads include `X-Content-Type-Options: nosniff`.
- **Malware scanning trade-off:** Full antivirus/sandbox scanning (e.g., ClamAV / ICAP or cloud object-storage quarantine scanning) is omitted in this local/containerized setup to keep dependencies lightweight; in production, `AttachmentStorageService.readAndValidateFile` provides the integration point to stream bytes to an AV scanner before persistence.

## Event Delivery & Audit Log Scope

- **Kafka delivery guarantee (`BEST_EFFORT_AT_MOST_ONCE`):** `KafkaEventService` increments `totalEventsPublished` only upon broker acknowledgement (`CompletableFuture` success) and increments `totalEventsFailed` on broker errors or exceptions. When `APP_KAFKA_ENABLED=false`, events are dispatched via the local `IN_MEMORY_BUS`. Guaranteed delivery in production would require a transactional outbox table.
- **Audit log integrity & CSV export:** `AuditLog` is application-level append-only (`@Immutable` + `updatable = false` columns; production tamper-proofing requires DB-level `REVOKE UPDATE, DELETE` privileges). CSV exports (`GET /api/v1/admin/audit-logs/export`) neutralize spreadsheet formula injection (`=`, `+`, `-`, `@`, `\t`, `\r`) before RFC-4180 quoting and are capped at 10,000 rows (`timestamp DESC, id DESC`).

## Security, Migrations & Production Configuration

- **Schema migrations:** Flyway manages schema evolution (`src/main/resources/db/migration/common` and `postgresql`) with Hibernate `ddl-auto=validate`.
- **Fail-fast production secrets & profile gating:** `DataSeeder` only runs under `demo` or `dev` profiles. Non-demo deployments require an explicit `APP_JWT_SECRET` (minimum 32 bytes) and fail fast on startup if missing or using the placeholder/default secret.
- **Rate limiting & trusted proxies:** `LoginRateLimiterService` enforces per-account lockout (5 consecutive failures) and per-IP `429` throttling (20 failures in 5 minutes) on `/api/v1/auth/login` with uniform `401` responses across unknown, locked, and bad-password cases; `OtpRateLimiterService` throttles OTP verification on `/api/v1/auth/reset-password-verify`. Forwarded client IP headers are honored only from CIDRs configured in `APP_TRUSTED_PROXIES`.
