package za.co.capi.dispute;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationTestcontainersTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("capi_disputes_test")
            .withUsername("capi")
            .withPassword("capi_test_secret");

    @Test
    void runsCommonAndPostgresqlMigrationsAndEnforcesVendorConstraints() throws Exception {
        MigrateResult result = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/common", "classpath:db/migration/postgresql")
                .load()
                .migrate();

        assertThat(result.migrationsExecuted).isEqualTo(3);

        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID customerId = UUID.randomUUID();
            insertCustomer(conn, customerId);

            UUID txId = UUID.randomUUID();
            insertSettledTransaction(conn, txId, customerId);

            // First active dispute for txId succeeds
            UUID firstDisputeId = UUID.randomUUID();
            insertDispute(conn, firstDisputeId, "DSP-PG-10001", txId, customerId, "LOGGED", new BigDecimal("250.00"));

            // Second active dispute for same txId must violate PostgreSQL partial unique index uq_disputes_active_transaction (V2)
            assertThatThrownBy(() -> insertDispute(
                    conn, UUID.randomUUID(), "DSP-PG-10002", txId, customerId, "IN_REVIEW", new BigDecimal("250.00")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_disputes_active_transaction");

            // Rejecting the first dispute allows filing a follow-up active dispute for txId
            try (PreparedStatement ps = conn.prepareStatement("UPDATE disputes SET status = 'REJECTED' WHERE id = ?")) {
                ps.setObject(1, firstDisputeId);
                ps.executeUpdate();
            }
            insertDispute(conn, UUID.randomUUID(), "DSP-PG-10003", txId, customerId, "LOGGED", new BigDecimal("250.00"));

            // Check constraint chk_disputes_amount_positive (V3) rejects amount <= 0
            assertThatThrownBy(() -> insertDispute(
                    conn, UUID.randomUUID(), "DSP-PG-10004", null, customerId, "LOGGED", BigDecimal.ZERO))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_disputes_amount_positive");

            // Sequence dispute_ref_seq (V3) yields values >= 10100
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT nextval('dispute_ref_seq')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(10100L);
            }
        }
    }

    private static void insertCustomer(Connection conn, UUID customerId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO app_users (id, email, full_name, password_hash, role, user_domain, is_locked, token_version, created_at)
                VALUES (?, ?, 'Postgres Test User', '$2a$10$dummyhash', 'CUSTOMER', 'CUSTOMER', FALSE, 0, ?)
                """)) {
            ps.setObject(1, customerId);
            ps.setString(2, "pgtest+" + customerId + "@capi.test");
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private static void insertSettledTransaction(Connection conn, UUID txId, UUID customerId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO bank_transactions (id, customer_id, transaction_reference, merchant_name, payment_rail, amount, currency, transacted_at, status)
                VALUES (?, ?, ?, 'Checkers Test', 'CARD_POS', 250.00, 'ZAR', ?, 'SETTLED')
                """)) {
            ps.setObject(1, txId);
            ps.setObject(2, customerId);
            ps.setString(3, "TXN-PG-" + txId.toString().substring(0, 8));
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private static void insertDispute(
            Connection conn,
            UUID disputeId,
            String ref,
            UUID txId,
            UUID customerId,
            String status,
            BigDecimal amount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO disputes (id, dispute_reference, transaction_id, merchant_name, payment_rail, customer_id,
                                      assigned_domain, status, category, source_channel, amount, reason, created_at, version)
                VALUES (?, ?, ?, 'Checkers Test', 'CARD_POS', ?, 'INTERNAL_CAPI', ?, 'UNAUTHORIZED_TRANSACTION',
                        'WEB_PORTAL', ?, 'PostgreSQL migration constraint verification', ?, 0)
                """)) {
            ps.setObject(1, disputeId);
            ps.setString(2, ref);
            ps.setObject(3, txId);
            ps.setObject(4, customerId);
            ps.setString(5, status);
            ps.setBigDecimal(6, amount);
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }
}
