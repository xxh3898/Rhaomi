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
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import kr.co.rhaomi.backend.config.AdminBootstrap;
import kr.co.rhaomi.publisher.PublisherControlLoop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.RestController;

@ExtendWith(OutputCaptureExtension.class)
class ProductionInitialAdminTaskIntegrationTests {

    private static final String ADMIN_EMAIL = "INITIAL.ADMIN@example.com";
    private static final String ADMIN_PASSWORD = "synthetic-initial-password-123!";

    private final java.util.Set<String> credentialValues = ConcurrentHashMap.newKeySet();

    @AfterEach
    void should_keepCredentialsOutOfApplicationOutput(CapturedOutput output) {
        var captured = output.getAll();
        for (var value : credentialValues) {
            assertFalse(captured.contains(value), "application output에 credential이 없어야 합니다.");
            assertFalse(captured.contains(value.toLowerCase(java.util.Locale.ROOT)),
                    "application output에 정규화된 credential이 없어야 합니다.");
        }
        assertFalse(Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}")
                .matcher(captured).find(), "application output에 BCrypt hash가 없어야 합니다.");
    }

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
            assertTrue(passwordHash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}"));
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
    void should_allowAtMostOneCommit_when_twoProvisioningTransactionsOverlap() throws Exception {
        withMigratedSchema(schemaUrl -> {
            try (var context = initialAdminServiceContext(schemaUrl)) {
                var provisioningService = context.getBean(InitialAdminProvisioningService.class);
                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                var outcomes = new ArrayList<TaskOutcome>();
                try (var executor = Executors.newFixedThreadPool(2)) {
                    var futures = List.of(
                            executor.submit(() -> runConcurrentProvisioning(
                                    provisioningService,
                                    "concurrent.one@example.com",
                                    "concurrent-password-one-123!",
                                    ready,
                                    start)),
                            executor.submit(() -> runConcurrentProvisioning(
                                    provisioningService,
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
                assertTrue(outcomes.stream()
                        .filter(outcome -> !outcome.success())
                        .allMatch(outcome -> messageChain(outcome.failure())
                                .contains("INITIAL_ADMIN_ALREADY_PROVISIONED")));
                assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM admin_users"));
                var combinedEvidence = outcomes.stream()
                        .map(outcome -> messageChain(outcome.failure()))
                        .reduce("", String::concat);
                assertFalse(combinedEvidence.contains("concurrent-password-one-123!"));
                assertFalse(combinedEvidence.contains("concurrent-password-two-123!"));
                assertFalse(combinedEvidence.contains("concurrent.one@example.com"));
                assertFalse(combinedEvidence.contains("concurrent.two@example.com"));
            }
        });
    }

    @Test
    void should_leaveDatabaseEmpty_when_insertFailsInsideTransaction() throws Exception {
        withMigratedSchema(schemaUrl -> {
            // 실제 admin INSERT 뒤 민감정보가 없는 guard row에서 실패시켜 DB DETAIL을 안전하게 유지한다.
            execute(schemaUrl, """
                    CREATE TABLE initial_admin_rollback_guard (
                        marker INTEGER CONSTRAINT ck_test_initial_admin_rollback_guard CHECK (FALSE)
                    )
                    """);
            execute(schemaUrl, """
                    CREATE FUNCTION reject_initial_admin_insert() RETURNS TRIGGER AS $$
                    BEGIN
                        IF (SELECT COUNT(*) FROM admin_users) <> 1 THEN
                            RAISE EXCEPTION 'TEST_ADMIN_INSERT_NOT_OBSERVED';
                        END IF;
                        INSERT INTO initial_admin_rollback_guard (marker) VALUES (1);
                        RETURN NULL;
                    END;
                    $$ LANGUAGE plpgsql
                    """);
            execute(schemaUrl, """
                    CREATE TRIGGER reject_initial_admin_insert AFTER INSERT ON admin_users
                    FOR EACH STATEMENT EXECUTE FUNCTION reject_initial_admin_insert()
                    """);

            try (var context = initialAdminServiceContext(schemaUrl)) {
                rememberCredentials(ADMIN_EMAIL, ADMIN_PASSWORD);
                var failure = assertThrows(RuntimeException.class, () -> context
                        .getBean(InitialAdminProvisioningService.class)
                        .provision(new InitialAdminCredential(ADMIN_EMAIL, ADMIN_PASSWORD)));
                assertTrue(hasSqlState(failure, "23514"), "실제 PostgreSQL CHECK 실패가 필요합니다.");
                assertTrue(messageChain(failure).contains("ck_test_initial_admin_rollback_guard"));
            }
            assertEquals(0L, queryLong(schemaUrl, "SELECT COUNT(*) FROM admin_users"));
            assertEquals(0L, queryLong(schemaUrl, "SELECT COUNT(*) FROM initial_admin_rollback_guard"));

            var exception = assertThrows(
                    RuntimeException.class,
                    () -> runInitialAdmin(
                            schemaUrl,
                            ADMIN_EMAIL,
                            ADMIN_PASSWORD,
                            new PrintStream(new ByteArrayOutputStream())));

            assertEquals(0L, queryLong(schemaUrl, "SELECT COUNT(*) FROM admin_users"));
            assertEquals(0L, queryLong(schemaUrl, "SELECT COUNT(*) FROM initial_admin_rollback_guard"));
            assertFalse(messageChain(exception).contains(ADMIN_EMAIL));
            assertFalse(messageChain(exception).contains(ADMIN_PASSWORD));
            assertEquals(
                    "INITIAL_ADMIN_PROVISIONING_FAILED",
                    deepestMessage(exception));
        });
    }

    private TaskOutcome runConcurrentProvisioning(
            InitialAdminProvisioningService provisioningService,
            String email,
            String password,
            CountDownLatch ready,
            CountDownLatch start) {
        rememberCredentials(email, password);
        ready.countDown();
        try {
            if (!start.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency gate timeout");
            }
            provisioningService.provision(new InitialAdminCredential(email, password));
            return new TaskOutcome(true, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new TaskOutcome(false, new IllegalStateException("concurrency gate interrupted"));
        } catch (Throwable throwable) {
            return new TaskOutcome(false, throwable);
        }
    }

    private AnnotationConfigApplicationContext initialAdminServiceContext(String schemaUrl) {
        var context = new AnnotationConfigApplicationContext();
        var properties = new HashMap<String, Object>();
        properties.put("spring.datasource.url", schemaUrl);
        properties.put(
                "spring.datasource.username", requiredEnvironment("SPRING_DATASOURCE_USERNAME"));
        properties.put(
                "spring.datasource.password", requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
        properties.put("spring.flyway.enabled", false);
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("spring.main.web-application-type", "none");
        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("initialAdminConcurrencyTest", properties));
        context.register(ProductionInitialAdminTaskConfiguration.class);
        context.refresh();
        return context;
    }

    private org.springframework.context.ConfigurableApplicationContext runInitialAdmin(
            String schemaUrl, String email, String password, PrintStream output) {
        rememberCredentials(email, password);
        return ProductionDatabaseTaskApplication.runInitialAdmin(
                taskArguments(schemaUrl), () -> new InitialAdminCredential(email, password), output);
    }

    private void rememberCredentials(String email, String password) {
        credentialValues.add(email);
        credentialValues.add(password);
    }

    private boolean hasSqlState(Throwable failure, String sqlState) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException
                    && sqlState.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
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

    private record TaskOutcome(boolean success, Throwable failure) {}

    @FunctionalInterface
    private interface SchemaAction {
        void run(String schemaUrl) throws Exception;
    }
}
