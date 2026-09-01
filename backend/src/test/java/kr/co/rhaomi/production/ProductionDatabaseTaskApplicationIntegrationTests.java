package kr.co.rhaomi.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.co.rhaomi.backend.config.AdminBootstrap;
import kr.co.rhaomi.publisher.PublisherControlLoop;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.bind.annotation.RestController;

class ProductionDatabaseTaskApplicationIntegrationTests {

    @Test
    void should_migrateAndValidateFreshV9Schema_withoutWebOrLongLivedApplicationBeans() throws Exception {
        var schema = schemaName();
        var baseUrl = requiredEnvironment("SPRING_DATASOURCE_URL");
        var username = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
        var password = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");
        createSchema(baseUrl, username, password, schema);
        var schemaUrl = withCurrentSchema(baseUrl, schema);

        try {
            try (var context = ProductionDatabaseTaskApplication.run(new String[] {
                ProductionDatabaseTaskApplication.MIGRATE_ARGUMENT,
                "--spring.main.web-application-type=servlet",
                "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + schemaUrl,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password
            })) {
                assertTaskContext(context, true);
            }

            assertEquals("9", latestFlywayVersion(schemaUrl, username, password));

            try (var context = ProductionDatabaseTaskApplication.run(new String[] {
                ProductionDatabaseTaskApplication.SCHEMA_VALIDATE_ARGUMENT,
                "--spring.main.web-application-type=servlet",
                "--spring.flyway.enabled=true",
                "--spring.datasource.url=" + schemaUrl,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password
            })) {
                assertTaskContext(context, false);
            }
        } finally {
            dropSchema(baseUrl, username, password, schema);
        }
    }

    @Test
    void should_failSchemaValidation_when_flywaySchemaIsMissing() throws Exception {
        var schema = schemaName();
        var baseUrl = requiredEnvironment("SPRING_DATASOURCE_URL");
        var username = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
        var password = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");
        createSchema(baseUrl, username, password, schema);

        try {
            var schemaUrl = withCurrentSchema(baseUrl, schema);
            assertThrows(
                    RuntimeException.class,
                    () -> ProductionDatabaseTaskApplication.run(new String[] {
                        ProductionDatabaseTaskApplication.SCHEMA_VALIDATE_ARGUMENT,
                        "--spring.datasource.url=" + schemaUrl,
                        "--spring.datasource.username=" + username,
                        "--spring.datasource.password=" + password
                    }));
        } finally {
            dropSchema(baseUrl, username, password, schema);
        }
    }

    private void assertTaskContext(
            org.springframework.context.ConfigurableApplicationContext context,
            boolean flywayEnabled) {
        assertFalse(context instanceof WebServerApplicationContext);
        assertEquals(
                flywayEnabled,
                context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class));
        assertEquals(
                "validate",
                context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
        assertTrue(context.getBeansWithAnnotation(RestController.class).isEmpty());
        assertTrue(context.getBeansOfType(AdminBootstrap.class).isEmpty());
        assertTrue(context.getBeansOfType(PublisherControlLoop.class).isEmpty());
    }

    private String latestFlywayVersion(String url, String username, String password)
            throws SQLException {
        try (var connection = DriverManager.getConnection(url, username, password);
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT version FROM flyway_schema_history "
                                + "WHERE success = TRUE AND version IS NOT NULL "
                                + "ORDER BY installed_rank DESC LIMIT 1")) {
            result.next();
            return result.getString(1);
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

    private String schemaName() {
        return "production_task_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test database environment is missing");
        }
        return value;
    }
}
