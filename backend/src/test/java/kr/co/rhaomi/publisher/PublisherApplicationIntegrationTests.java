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
            "--rhaomi.publisher.lock-file=/tmp/rhaomi-publisher-context-test.lock",
            "--rhaomi.publisher.executor.node-executable=/bin/sh",
            "--rhaomi.publisher.executor.release-script=/bin/true",
            "--rhaomi.publisher.executor.build-api-internal-url=https://build.example/",
            "--rhaomi.publisher.executor.build-api-credential=" + "a".repeat(64),
            "--rhaomi.publisher.executor.source-root=/tmp",
            "--rhaomi.publisher.executor.work-root=/tmp/rhaomi-publisher-work",
            "--rhaomi.publisher.executor.release-root=/tmp/rhaomi-public/releases",
            "--rhaomi.publisher.executor.current-link=/tmp/rhaomi-public/current",
            "--rhaomi.publisher.executor.previous-link=/tmp/rhaomi-public/previous",
            "--rhaomi.publisher.executor.public-site-url=https://site.example/",
            "--rhaomi.publisher.executor.code-sha=" + "b".repeat(40),
            "--rhaomi.publisher.executor.code-image-tag=sha-" + "b".repeat(40),
            "--rhaomi.publisher.executor.code-image-digest=sha256:" + "c".repeat(64),
            "--rhaomi.publisher.executor.flyway-version=10",
            "--rhaomi.publisher.executor.sbom-reference=sha256:" + "d".repeat(64)
        })) {
            assertFalse(context instanceof WebServerApplicationContext);
            assertEquals(1, context.getBeansOfType(PublisherControlLoop.class).size());
            assertEquals(1, context.getBeansOfType(PublisherLifecycle.class).size());
            assertEquals(1, context.getBeansOfType(NodePublicationBuildExecutor.class).size());
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
