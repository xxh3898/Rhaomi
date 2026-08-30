package kr.co.rhaomi.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import kr.co.rhaomi.backend.build.BuildServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSecurityGuardTests {

    @Test
    void should_rejectInsecureSessionCookie_when_profileIsProduction() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        var guard = new ProductionSecurityGuard(environment, false, configuredBuildService());

        assertThrows(IllegalStateException.class, () -> guard.run(null));
    }

    @Test
    void should_acceptSecurityConfiguration_when_profileIsProductionAndValuesAreValid() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        var guard = new ProductionSecurityGuard(environment, true, configuredBuildService());

        assertDoesNotThrow(() -> guard.run(null));
    }

    @Test
    void should_rejectMissingOrMalformedBuildToken_when_profileIsProduction() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSecurityGuard(
                                environment, true, new BuildServiceProperties(null))
                        .run(null));
        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSecurityGuard(
                                environment, true, new BuildServiceProperties("A".repeat(64)))
                        .run(null));
    }

    @Test
    void should_acceptDisabledBuildServiceAndInsecureCookie_when_profileIsNotProduction() {
        var guard = new ProductionSecurityGuard(
                new MockEnvironment(), false, new BuildServiceProperties(null));

        assertDoesNotThrow(() -> guard.run(null));
    }

    private BuildServiceProperties configuredBuildService() {
        return new BuildServiceProperties("a".repeat(64));
    }
}
