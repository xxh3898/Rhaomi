package kr.co.rhaomi.publisher;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public record PublisherSettings(
        String owner,
        Duration idlePollInterval,
        Duration leaseDuration,
        Duration leaseRenewalInterval,
        Duration shutdownTimeout,
        Path lockFile) {

    public static final Duration DEBOUNCE_WINDOW = Duration.ofSeconds(30);

    private static final int MAX_OWNER_CODE_POINTS = 128;
    private static final Duration MIN_IDLE_POLL_INTERVAL = Duration.ofMillis(10);
    private static final Duration MAX_IDLE_POLL_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MIN_LEASE_DURATION = Duration.ofSeconds(1);
    private static final Duration MAX_LEASE_DURATION = Duration.ofHours(1);
    private static final Duration MIN_LEASE_RENEWAL_INTERVAL = Duration.ofMillis(10);
    private static final Duration MIN_SHUTDOWN_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAX_SHUTDOWN_TIMEOUT = Duration.ofMinutes(1);

    public PublisherSettings {
        owner = validateOwner(owner);
        idlePollInterval = validateDuration(
                idlePollInterval,
                MIN_IDLE_POLL_INTERVAL,
                MAX_IDLE_POLL_INTERVAL,
                "idlePollInterval");
        leaseDuration = validateDuration(
                leaseDuration, MIN_LEASE_DURATION, MAX_LEASE_DURATION, "leaseDuration");
        leaseRenewalInterval = validateDuration(
                leaseRenewalInterval,
                MIN_LEASE_RENEWAL_INTERVAL,
                leaseDuration,
                "leaseRenewalInterval");
        shutdownTimeout = validateDuration(
                shutdownTimeout,
                MIN_SHUTDOWN_TIMEOUT,
                MAX_SHUTDOWN_TIMEOUT,
                "shutdownTimeout");
        lockFile = validateLockFile(lockFile);

        if (leaseRenewalInterval.multipliedBy(2).compareTo(leaseDuration) > 0) {
            throw new IllegalArgumentException(
                    "leaseRenewalInterval must be at most half of leaseDuration");
        }
    }

    private static String validateOwner(String value) {
        Objects.requireNonNull(value, "owner");
        var codePointCount = value.codePointCount(0, value.length());
        var containsControl = value.codePoints().anyMatch(Character::isISOControl);
        if (value.isBlank()
                || !value.equals(value.strip())
                || codePointCount > MAX_OWNER_CODE_POINTS
                || containsControl) {
            throw new IllegalArgumentException("Invalid publisher owner");
        }
        return value;
    }

    private static Duration validateDuration(
            Duration value, Duration minimum, Duration maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Invalid publisher duration: " + name);
        }
        return value;
    }

    private static Path validateLockFile(Path value) {
        Objects.requireNonNull(value, "lockFile");
        var normalized = value.toAbsolutePath().normalize();
        if (!value.isAbsolute() || normalized.getFileName() == null) {
            throw new IllegalArgumentException("Publisher lock file must be an absolute file path");
        }
        return normalized;
    }
}
