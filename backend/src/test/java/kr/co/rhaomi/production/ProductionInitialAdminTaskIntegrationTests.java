package kr.co.rhaomi.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.co.rhaomi.backend.config.AdminBootstrap;
import kr.co.rhaomi.publisher.PublisherControlLoop;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.RestController;

class ProductionInitialAdminTaskIntegrationTests {

    private static final String ADMIN_EMAIL = "INITIAL.ADMIN@example.com";
    private static final String ADMIN_PASSWORD = "synthetic-initial-password-123!";

    @Test
    void should_createExactlyOneHashedAdmin_withoutWebOrLongLivedBeans() throws Exception {
        withMigratedSchema(schemaUrl -> {
            var output = new ByteArrayOutputStream();
            try (var context = runInitialAdmin(
                    schemaUrl, ADMIN_EMAIL, ADMIN_PASSWORD, new PrintStream(output, true, StandardCharsets.UTF_8))) {
                assertFalse(context instanceof WebServerApplicationContext);
                assertTrue(context.getBeansWithAnnotation(RestController.class).isEmpty());
                assertTrue(context.getBeansOfType(AdminBootstrap.class).isEmpty());
                assertTrue(context.getBeansOfType(PublisherControlLoop.class).isEmpty());
                assertFalse(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class));
                assertEquals("validate", context.getEnvironment()
                        .getProperty("spring.jpa.hibernate.ddl-auto"));
            }

            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM admin_users"));
            assertEquals(
                    "initial.admin@example.com",
                    queryString(schemaUrl, "SELECT email FROM admin_users"));
            var passwordHash = queryString(schemaUrl, "SELECT password_hash FROM admin_users");
            assertNotEquals(ADMIN_PASSWORD, passwordHash);
            assertTrue(new BCryptPasswordEncoder(12).matches(ADMIN_PASSWORD, passwordHash));
            assertEquals(
                    0L,
                    queryLong(schemaUrl, "SELECT COUNT(*) FROM admin_webauthn_credentials"));
            assertEquals(
                    "{\"contract\":\"rhaomi-initial-admin-v1\","
                            + "\"status\":\"success\",\"administratorCount\":1}"
                            + System.lineSeparator(),
                    output.toString(StandardCharsets.UTF_8));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains(ADMIN_EMAIL));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains(ADMIN_PASSWORD));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains(passwordHash));
        });
    }

    @Test
    void should_rejectReplayAndPreserveExistingAdministrator() throws Exception {
        withMigratedSchema(schemaUrl -> {
            try (var ignored = runInitialAdmin(
                    schemaUrl,
                    ADMIN_EMAIL,
                    ADMIN_PASSWORD,
                    new PrintStream(new ByteArrayOutputStream()))) {}

            var exception = assertThrows(
                    RuntimeException.class,
                    () -> runInitialAdmin(
                            schemaUrl,
                            "second.admin@example.com",
                            "different-synthetic-password-123!",
                            new PrintStream(new ByteArrayOutputStream())));

            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM admin_users"));
            assertFalse(messageChain(exception).contains("second.admin@example.com"));
            assertFalse(messageChain(exception).contains("different-synthetic-password-123!"));
            assertTrue(messageChain(exception).contains("INITIAL_ADMIN_ALREADY_PROVISIONED"));
        });
    }

    @Test
    void should_allowAtMostOneCommit_when_twoTaskInvocationsOverlap() throws Exception {
        withMigratedSchema(schemaUrl -> {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var outcomes = new ArrayList<TaskOutcome>();
            try (var executor = Executors.newFixedThreadPool(2)) {
                var futures = List.of(
                        executor.submit(() -> runConcurrentTask(
                                schemaUrl,
                                "concurrent.one@example.com",
                                "concurrent-password-one-123!",
                                ready,
                                start)),
                        executor.submit(() -> runConcurrentTask(
                                schemaUrl,
                                "concurrent.two@example.com",
                                "concurrent-password-two-123!",
                                ready,
                                start)));

                assertTrue(ready.await(30, TimeUnit.SECONDS));
                start.countDown();
                for (var future : futures) {
                    outcomes.add(future.get(120, TimeUnit.SECONDS));
                }
            }

            assertEquals(1L, outcomes.stream().filter(TaskOutcome::success).count());
            assertEquals(1L, outcomes.stream().filter(outcome -> !outcome.success()).count());
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM admin_users"));
            var combinedEvidence = outcomes.stream()
                    .map(outcome -> outcome.output() + messageChain(outcome.failure()))
                    .reduce("", String::concat);
            assertFalse(combinedEvidence.contains("concurrent-password-one-123!"));
            assertFalse(combinedEvidence.contains("concurrent-password-two-123!"));
            assertFalse(combinedEvidence.contains("concurrent.one@example.com"));
            assertFalse(combinedEvidence.contains("concurrent.two@example.com"));
        });
    }

    @Test
    void should_leaveDatabaseEmpty_when_insertFailsInsideTransaction() throws Exception {
        withMigratedSchema(schemaUrl -> {
            execute(schemaUrl, "ALTER TABLE admin_users ADD CONSTRAINT ck_test_reject_admin CHECK (FALSE)");

            var exception = assertThrows(
                    RuntimeException.class,
                    () -> runInitialAdmin(
                            schemaUrl,
                            ADMIN_EMAIL,
                            ADMIN_PASSWORD,
                            new PrintStream(new ByteArrayOutputStream())));

            assertEquals(0L, queryLong(schemaUrl, "SELECT COUNT(*) FROM admin_users"));
            assertFalse(messageChain(exception).contains(ADMIN_EMAIL));
            assertFalse(messageChain(exception).contains(ADMIN_PASSWORD));
            assertEquals(
                    "INITIAL_ADMIN_PROVISIONING_FAILED",
                    deepestMessage(exception));
        });
    }

    private TaskOutcome runConcurrentTask(
            String schemaUrl,
            String email,
            String password,
            CountDownLatch ready,
            CountDownLatch start) {
        var output = new ByteArrayOutputStream();
        try {
            var credentialSource = (InitialAdminCredentialSource) () -> {
                ready.countDown();
                try {
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrency gate timeout");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("concurrency gate interrupted");
                }
                return new InitialAdminCredential(email, password);
            };
            try (var ignored = ProductionDatabaseTaskApplication.runInitialAdmin(
                    taskArguments(schemaUrl),
                    credentialSource,
                    new PrintStream(output, true, StandardCharsets.UTF_8))) {
                return new TaskOutcome(true, null, output.toString(StandardCharsets.UTF_8));
            }
        } catch (Throwable throwable) {
            return new TaskOutcome(false, throwable, output.toString(StandardCharsets.UTF_8));
        }
    }

    private org.springframework.context.ConfigurableApplicationContext runInitialAdmin(
            String schemaUrl, String email, String password, PrintStream output) {
        return ProductionDatabaseTaskApplication.runInitialAdmin(
                taskArguments(schemaUrl), () -> new InitialAdminCredential(email, password), output);
    }

    private String[] taskArguments(String schemaUrl) {
        return new String[] {
            ProductionDatabaseTaskApplication.INITIAL_ADMIN_ARGUMENT,
            "--spring.main.web-application-type=servlet",
            "--spring.flyway.enabled=true",
            "--spring.jpa.hibernate.ddl-auto=create-drop",
            "--spring.datasource.url=" + schemaUrl,
            "--spring.datasource.username=" + requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
            "--spring.datasource.password=" + requiredEnvironment("SPRING_DATASOURCE_PASSWORD")
        };
    }

    private void withMigratedSchema(SchemaAction action) throws Exception {
        var schema = "initial_admin_" + UUID.randomUUID().toString().replace("-", "");
        var baseUrl = requiredEnvironment("SPRING_DATASOURCE_URL");
        var username = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
        var password = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");
        createSchema(baseUrl, username, password, schema);
        var schemaUrl = withCurrentSchema(baseUrl, schema);
        try {
            try (var ignored = ProductionDatabaseTaskApplication.run(new String[] {
                ProductionDatabaseTaskApplication.MIGRATE_ARGUMENT,
                "--spring.datasource.url=" + schemaUrl,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password
            })) {}
            action.run(schemaUrl);
        } finally {
            dropSchema(baseUrl, username, password, schema);
        }
    }

    private long queryLong(String url, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        url,
                        requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                        requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String queryString(String url, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        url,
                        requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                        requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private void execute(String url, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        url,
                        requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                        requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void createSchema(String url, String username, String password, String schema)
            throws SQLException {
        try (var connection = DriverManager.getConnection(url, username, password);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    private void dropSchema(String url, String username, String password, String schema)
            throws SQLException {
        try (var connection = DriverManager.getConnection(url, username, password);
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private String withCurrentSchema(String url, String schema) {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test database environment is missing");
        }
        return value;
    }

    private String messageChain(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        var message = new StringBuilder();
        var current = throwable;
        while (current != null) {
            if (current.getMessage() != null) {
                message.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return message.toString();
    }

    private String deepestMessage(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private record TaskOutcome(boolean success, Throwable failure, String output) {}

    @FunctionalInterface
    private interface SchemaAction {
        void run(String schemaUrl) throws Exception;
    }
}
