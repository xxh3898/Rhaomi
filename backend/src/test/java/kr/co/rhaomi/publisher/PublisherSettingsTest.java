package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PublisherSettingsTest {

    @Test
    void should_acceptBoundedNonSecretRuntimeSettings_when_valuesAreValid() {
        var settings = settings("publisher-01", Duration.ofSeconds(30), Duration.ofMinutes(2));

        assertEquals("publisher-01", settings.owner());
        assertEquals(Duration.ofSeconds(30), settings.leaseRenewalInterval());
        assertEquals(PublisherSettings.DEBOUNCE_WINDOW, Duration.ofSeconds(30));
    }

    @Test
    void should_rejectOwnerOver128CodePoints_when_settingsAreCreated() {
        assertThrows(
                IllegalArgumentException.class,
                () -> settings("가".repeat(129), Duration.ofSeconds(30), Duration.ofMinutes(2)));
    }

    @Test
    void should_rejectRenewalOverHalfLease_when_settingsAreCreated() {
        assertThrows(
                IllegalArgumentException.class,
                () -> settings("publisher", Duration.ofSeconds(31), Duration.ofMinutes(1)));
    }

    @Test
    void should_rejectNearBusySpinPoll_when_settingsAreCreated() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PublisherSettings(
                        "publisher",
                        Duration.ofNanos(1),
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(1),
                        Path.of("/tmp/rhaomi-publisher-settings.lock")));
    }

    private PublisherSettings settings(String owner, Duration renewal, Duration lease) {
        return new PublisherSettings(
                owner,
                Duration.ofSeconds(1),
                lease,
                renewal,
                Duration.ofSeconds(10),
                Path.of("/tmp/rhaomi-publisher-settings.lock"));
    }
}
