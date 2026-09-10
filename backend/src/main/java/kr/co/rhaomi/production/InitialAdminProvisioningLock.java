package kr.co.rhaomi.production;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class InitialAdminProvisioningLock {

    static final long LOCK_KEY = 0x5248414F4D493936L;

    private final JdbcTemplate jdbcTemplate;

    InitialAdminProvisioningLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void acquire() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("INITIAL_ADMIN_TRANSACTION_REQUIRED");
        }
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (var statement =
                    connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, LOCK_KEY);
                statement.execute();
            }
            return null;
        });
    }
}
