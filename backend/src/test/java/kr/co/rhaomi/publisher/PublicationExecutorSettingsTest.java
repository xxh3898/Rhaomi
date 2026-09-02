package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class PublicationExecutorSettingsTest {

    @Test
    void should_allowlistChildEnvironmentAndRedactSettings_when_configurationIsValid() {
        var environment = environment();
        environment.put("UNRELATED_SECRET", "must-not-pass");

        var settings = new PublicationExecutorSettings(
                Path.of("/bin/sh"),
                Path.of("/bin/true"),
                Duration.ofSeconds(1),
                environment);

        assertEquals("a".repeat(64), settings.environment().get("BUILD_API_CREDENTIAL"));
        assertEquals("5", settings.environment().get("RHAOMI_RELEASE_RETENTION"));
        assertFalse(settings.environment().containsKey("UNRELATED_SECRET"));
        assertEquals("PublicationExecutorSettings[redacted]", settings.toString());
    }

    @Test
    void should_normalizeRootOrigins_when_trailingSlashIsOmitted() {
        var environment = environment();
        environment.put("BUILD_API_INTERNAL_URL", "http://backend:8080");
        environment.put("PUBLIC_SITE_URL", "https://site.example");

        var settings = new PublicationExecutorSettings(
                Path.of("/bin/sh"),
                Path.of("/bin/true"),
                Duration.ofSeconds(1),
                environment);

        assertEquals(
                "http://backend:8080/",
                settings.environment().get("BUILD_API_INTERNAL_URL"));
        assertEquals(
                "https://site.example/",
                settings.environment().get("PUBLIC_SITE_URL"));
    }

    @Test
    void should_rejectMalformedCredentialUrlPathRelationshipAndTimeout_when_configurationIsInvalid() {
        var malformedCredential = environment();
        malformedCredential.put("BUILD_API_CREDENTIAL", "not-a-token");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PublicationExecutorSettings(
                        Path.of("/bin/sh"),
                        Path.of("/bin/true"),
                        Duration.ofSeconds(1),
                        malformedCredential));

        var relativePath = environment();
        relativePath.put("RHAOMI_PUBLIC_RELEASE_ROOT", "relative/releases");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PublicationExecutorSettings(
                        Path.of("/bin/sh"),
                        Path.of("/bin/true"),
                        Duration.ofSeconds(1),
                        relativePath));

        var credentialInUrl = environment();
        credentialInUrl.put("BUILD_API_INTERNAL_URL", "https://user:secret@build.example/");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PublicationExecutorSettings(
                        Path.of("/bin/sh"),
                        Path.of("/bin/true"),
                        Duration.ofSeconds(1),
                        credentialInUrl));

        var insecurePublicUrl = environment();
        insecurePublicUrl.put("PUBLIC_SITE_URL", "http://site.example/");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PublicationExecutorSettings(
                        Path.of("/bin/sh"),
                        Path.of("/bin/true"),
                        Duration.ofSeconds(1),
                        insecurePublicUrl));

        var nonSiblingCurrent = environment();
        nonSiblingCurrent.put("RHAOMI_PUBLIC_CURRENT_LINK", "/tmp/other/current");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PublicationExecutorSettings(
                        Path.of("/bin/sh"),
                        Path.of("/bin/true"),
                        Duration.ofSeconds(1),
                        nonSiblingCurrent));

        var excessiveTimeout = environment();
        excessiveTimeout.put("RHAOMI_PUBLISHER_BUILD_TIMEOUT_MS", "3600001");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PublicationExecutorSettings(
                        Path.of("/bin/sh"),
                        Path.of("/bin/true"),
                        Duration.ofSeconds(1),
                        excessiveTimeout));
    }

    static LinkedHashMap<String, String> environment() {
        var environment = new LinkedHashMap<String, String>();
        environment.put("BUILD_API_INTERNAL_URL", "https://build.example/");
        environment.put("BUILD_API_CREDENTIAL", "a".repeat(64));
        environment.put("RHAOMI_PUBLISHER_SOURCE_ROOT", "/tmp/source");
        environment.put("RHAOMI_PUBLISHER_WORK_ROOT", "/tmp/work");
        environment.put("RHAOMI_PUBLIC_RELEASE_ROOT", "/tmp/public/releases");
        environment.put("RHAOMI_PUBLIC_CURRENT_LINK", "/tmp/public/current");
        environment.put("RHAOMI_PUBLIC_PREVIOUS_LINK", "/tmp/public/previous");
        environment.put("PUBLIC_SITE_URL", "https://site.example/");
        environment.put("RHAOMI_CODE_SHA", "b".repeat(40));
        environment.put("RHAOMI_CODE_IMAGE_TAG", "sha-" + "b".repeat(40));
        environment.put("RHAOMI_CODE_IMAGE_DIGEST", "sha256:" + "c".repeat(64));
        environment.put("RHAOMI_FLYWAY_VERSION", "10");
        environment.put("RHAOMI_SBOM_REFERENCE", "sha256:" + "d".repeat(64));
        environment.put("RHAOMI_RELEASE_RETENTION", "5");
        environment.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        return environment;
    }
}
