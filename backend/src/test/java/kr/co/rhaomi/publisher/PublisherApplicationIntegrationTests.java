package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.bind.annotation.RestController;

class PublisherApplicationIntegrationTests {

    @Test
    void should_startNonWebWithoutControllers_when_explicitPublisherModeRuns() {
        var dataSourceUrl = requiredEnvironment("SPRING_DATASOURCE_URL");
        var dataSourceUsername = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
        var dataSourcePassword = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");

        try (var context = PublisherApplication.run(new String[] {
            PublisherApplication.MODE_ARGUMENT,
            "--spring.main.web-application-type=servlet",
            "--spring.flyway.enabled=true",
            "--spring.datasource.url=" + dataSourceUrl,
            "--spring.datasource.username=" + dataSourceUsername,
            "--spring.datasource.password=" + dataSourcePassword,
            "--rhaomi.publisher.owner=publisher-context-test",
            "--rhaomi.publisher.auto-start=false",
            "--rhaomi.publisher.lock-file=/tmp/rhaomi-publisher-context-test.lock"
        })) {
            assertFalse(context instanceof WebServerApplicationContext);
            assertEquals(1, context.getBeansOfType(PublisherControlLoop.class).size());
            assertEquals(1, context.getBeansOfType(PublisherLifecycle.class).size());
            assertTrue(context.getBeansWithAnnotation(RestController.class).isEmpty());
            assertFalse(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class));
            assertTrue(Arrays.asList(context.getEnvironment().getActiveProfiles())
                    .contains("publisher"));
        }
    }

    private String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test database environment is missing");
        }
        return value;
    }
}
