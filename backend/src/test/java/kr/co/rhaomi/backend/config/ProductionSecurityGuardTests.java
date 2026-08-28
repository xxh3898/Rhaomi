package kr.co.rhaomi.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSecurityGuardTests {

    @Test
    void rejectsInsecureSessionCookieInProduction() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        var guard = new ProductionSecurityGuard(environment, false);

        assertThrows(IllegalStateException.class, () -> guard.run(null));
    }

    @Test
    void acceptsSecureSessionCookieInProduction() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        var guard = new ProductionSecurityGuard(environment, true);

        assertDoesNotThrow(() -> guard.run(null));
    }

    @Test
    void acceptsLocalHttpWithoutSecureCookie() {
        var guard = new ProductionSecurityGuard(new MockEnvironment(), false);

        assertDoesNotThrow(() -> guard.run(null));
    }
}
