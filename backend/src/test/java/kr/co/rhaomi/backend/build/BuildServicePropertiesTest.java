package kr.co.rhaomi.backend.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildServicePropertiesTest {

    @Test
    void should_matchOnlyExactLowercaseHexToken_when_configurationIsValid() {
        var token = "0123456789abcdef".repeat(4);
        var properties = new BuildServiceProperties(token);

        assertTrue(properties.isConfigured());
        assertTrue(properties.matches(token));
        assertFalse(properties.matches("f".repeat(64)));
        assertFalse(properties.matches(token.toUpperCase()));
        assertFalse(properties.matches(token + "0"));
        assertFalse(properties.matches(token.substring(1)));
        assertFalse(properties.toString().contains(token));
    }

    @Test
    void should_remainDisabledAndNotMatch_when_configurationIsMissingOrMalformed() {
        for (var value : new String[] {"", "a".repeat(63), "a".repeat(65), "G".repeat(64)}) {
            var properties = new BuildServiceProperties(value);
            assertFalse(properties.isConfigured());
            assertFalse(properties.matches("a".repeat(64)));
            if (!value.isEmpty()) {
                assertFalse(properties.toString().contains(value));
            }
        }

        var missing = new BuildServiceProperties(null);
        assertFalse(missing.isConfigured());
        assertFalse(missing.matches("a".repeat(64)));
    }
}
