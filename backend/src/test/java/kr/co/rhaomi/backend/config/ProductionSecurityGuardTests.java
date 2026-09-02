package kr.co.rhaomi.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import kr.co.rhaomi.backend.build.BuildServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSecurityGuardTests {

    @Test
    void should_rejectInsecureSessionCookie_when_profileIsProduction() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        var guard = new ProductionSecurityGuard(
                environment, false, configuredBuildService(), validWebAuthn());

        assertThrows(IllegalStateException.class, () -> guard.run(null));
    }

    @Test
    void should_acceptSecurityConfiguration_when_profileIsProductionAndValuesAreValid() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        var guard = new ProductionSecurityGuard(
                environment, true, configuredBuildService(), validWebAuthn());

        assertDoesNotThrow(() -> guard.run(null));
    }

    @Test
    void should_rejectMissingOrMalformedBuildToken_when_profileIsProduction() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSecurityGuard(
                                environment,
                                true,
                                new BuildServiceProperties(null),
                                validWebAuthn())
                        .run(null));
        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSecurityGuard(
                                environment,
                                true,
                                new BuildServiceProperties("A".repeat(64)),
                                validWebAuthn())
                        .run(null));
    }

    @Test
    void should_acceptDisabledBuildServiceAndInsecureCookie_when_profileIsNotProduction() {
        var guard = new ProductionSecurityGuard(
                new MockEnvironment(),
                false,
                new BuildServiceProperties(null),
                new AdminWebAuthnProperties(false, null, null, null, null));

        assertDoesNotThrow(() -> guard.run(null));
    }

    @Test
    void should_rejectDisabledOrInvalidWebAuthn_when_profileIsProduction() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSecurityGuard(
                                environment,
                                true,
                                configuredBuildService(),
                                new AdminWebAuthnProperties(
                                        false,
                                        "admin.example.com",
                                        "https://admin.example.com",
                                        "Rhaomi Admin",
                                        Duration.ofMinutes(5)))
                        .run(null));
        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSecurityGuard(
                                environment,
                                true,
                                configuredBuildService(),
                                new AdminWebAuthnProperties(
                                        true,
                                        "admin.example.com",
                                        "http://admin.example.com",
                                        "Rhaomi Admin",
                                        Duration.ofMinutes(5)))
                        .run(null));
        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSecurityGuard(
                                environment,
                                true,
                                configuredBuildService(),
                                new AdminWebAuthnProperties(
                                        true,
                                        "admin.example.com",
                                        "https://evil.example.net",
                                        "Rhaomi Admin",
                                        Duration.ofMinutes(5)))
                        .run(null));
        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSecurityGuard(
                                environment,
                                true,
                                configuredBuildService(),
                                new AdminWebAuthnProperties(
                                        true,
                                        "admin.example.com",
                                        "https://admin.example.com",
                                        "Rhaomi Admin",
                                        Duration.ofSeconds(59)))
                        .run(null));
    }

    private BuildServiceProperties configuredBuildService() {
        return new BuildServiceProperties("a".repeat(64));
    }

    private AdminWebAuthnProperties validWebAuthn() {
        return new AdminWebAuthnProperties(
                true,
                "admin.example.com",
                "https://admin.example.com",
                "Rhaomi Admin",
                Duration.ofMinutes(5));
    }
}
