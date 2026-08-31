package kr.co.rhaomi.publisher;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

final class PublicationExecutorSettings {

    private static final Pattern CREDENTIAL = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CODE_SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern IMAGE_TAG =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/@:-]{0,254}");
    private static final Pattern FLYWAY = Pattern.compile("(?:0|[1-9][0-9]{0,8})");
    private static final Duration MIN_TERMINATION_GRACE = Duration.ofMillis(100);
    private static final Duration MAX_TERMINATION_GRACE = Duration.ofSeconds(10);

    private final Path nodeExecutable;
    private final Path releaseScript;
    private final Duration terminationGrace;
    private final Map<String, String> environment;

    PublicationExecutorSettings(
            Path nodeExecutable,
            Path releaseScript,
            Duration terminationGrace,
            Map<String, String> environment) {
        this.nodeExecutable = executable(nodeExecutable);
        this.releaseScript = regularFile(releaseScript, "releaseScript");
        this.terminationGrace = duration(terminationGrace);
        this.environment = Map.copyOf(validateEnvironment(environment));
    }

    Path nodeExecutable() {
        return nodeExecutable;
    }

    Path releaseScript() {
        return releaseScript;
    }

    Duration terminationGrace() {
        return terminationGrace;
    }

    Map<String, String> environment() {
        return environment;
    }

    private static Path executable(Path value) {
        var path = regularFile(value, "nodeExecutable");
        if (!Files.isExecutable(path)) {
            throw new IllegalArgumentException("Publisher node executable is invalid");
        }
        return path;
    }

    private static Path regularFile(Path value, String name) {
        Objects.requireNonNull(value, name);
        var path = value.toAbsolutePath().normalize();
        if (!value.isAbsolute() || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Publisher executable path is invalid");
        }
        return path;
    }

    private static Duration duration(Duration value) {
        Objects.requireNonNull(value, "terminationGrace");
        if (value.compareTo(MIN_TERMINATION_GRACE) < 0
                || value.compareTo(MAX_TERMINATION_GRACE) > 0) {
            throw new IllegalArgumentException("Publisher termination grace is invalid");
        }
        return value;
    }

    private static Map<String, String> validateEnvironment(Map<String, String> source) {
        Objects.requireNonNull(source, "environment");
        var result = new LinkedHashMap<String, String>();
        result.put(
                "BUILD_API_INTERNAL_URL",
                rootUrl(source, "BUILD_API_INTERNAL_URL", false));
        result.put(
                "BUILD_API_CREDENTIAL",
                pattern(source, "BUILD_API_CREDENTIAL", CREDENTIAL));
        var sourceRoot = absolutePath(source, "RHAOMI_PUBLISHER_SOURCE_ROOT");
        var workRoot = absolutePath(source, "RHAOMI_PUBLISHER_WORK_ROOT");
        var releaseRoot = absolutePath(source, "RHAOMI_PUBLIC_RELEASE_ROOT");
        var currentLink = absolutePath(source, "RHAOMI_PUBLIC_CURRENT_LINK");
        var previousLink = absolutePath(source, "RHAOMI_PUBLIC_PREVIOUS_LINK");
        validateReleasePaths(sourceRoot, workRoot, releaseRoot, currentLink, previousLink);
        result.put("RHAOMI_PUBLISHER_SOURCE_ROOT", sourceRoot);
        result.put("RHAOMI_PUBLISHER_WORK_ROOT", workRoot);
        result.put("RHAOMI_PUBLIC_RELEASE_ROOT", releaseRoot);
        result.put("RHAOMI_PUBLIC_CURRENT_LINK", currentLink);
        result.put("RHAOMI_PUBLIC_PREVIOUS_LINK", previousLink);
        result.put("PUBLIC_SITE_URL", rootUrl(source, "PUBLIC_SITE_URL", true));
        result.put("RHAOMI_CODE_SHA", pattern(source, "RHAOMI_CODE_SHA", CODE_SHA));
        result.put(
                "RHAOMI_CODE_IMAGE_TAG",
                pattern(source, "RHAOMI_CODE_IMAGE_TAG", IMAGE_TAG));
        result.put(
                "RHAOMI_CODE_IMAGE_DIGEST",
                pattern(source, "RHAOMI_CODE_IMAGE_DIGEST", DIGEST));
        result.put(
                "RHAOMI_FLYWAY_VERSION",
                pattern(source, "RHAOMI_FLYWAY_VERSION", FLYWAY));
        result.put(
                "RHAOMI_SBOM_REFERENCE",
                pattern(source, "RHAOMI_SBOM_REFERENCE", DIGEST));
        var timeout = source.get("RHAOMI_PUBLISHER_BUILD_TIMEOUT_MS");
        if (timeout != null && !timeout.isBlank()) {
            result.put(
                    "RHAOMI_PUBLISHER_BUILD_TIMEOUT_MS",
                    boundedInteger(
                            source, "RHAOMI_PUBLISHER_BUILD_TIMEOUT_MS", 1_000, 3_600_000));
        }
        var retention = source.get("RHAOMI_RELEASE_RETENTION");
        if (retention != null && !retention.isBlank()) {
            result.put(
                    "RHAOMI_RELEASE_RETENTION",
                    pattern(
                            source,
                            "RHAOMI_RELEASE_RETENTION",
                            Pattern.compile("(?:[1-9]|[1-9][0-9]|100)")));
        }
        var path = source.get("PATH");
        if (path != null && !path.isBlank() && path.length() <= 8192) {
            result.put("PATH", path);
        }
        return result;
    }

    private static String absolutePath(Map<String, String> source, String key) {
        var value = bounded(source, key, 4096);
        var path = Path.of(value);
        if (path.getParent() == null
                || !path.isAbsolute()
                || !path.normalize().toString().equals(value)) {
            throw new IllegalArgumentException("Publisher path setting is invalid");
        }
        return value;
    }

    private static void validateReleasePaths(
            String sourceRoot,
            String workRoot,
            String releaseRoot,
            String currentLink,
            String previousLink) {
        var sourcePath = Path.of(sourceRoot);
        var workPath = Path.of(workRoot);
        var releasePath = Path.of(releaseRoot);
        var currentPath = Path.of(currentLink);
        var previousPath = Path.of(previousLink);
        var releaseParent = releasePath.getParent();
        if (sourcePath.equals(workPath)
                || sourcePath.equals(releasePath)
                || workPath.equals(releasePath)
                || currentPath.equals(previousPath)
                || !releaseParent.equals(currentPath.getParent())
                || !releaseParent.equals(previousPath.getParent())
                || currentPath.startsWith(releasePath)
                || previousPath.startsWith(releasePath)) {
            throw new IllegalArgumentException("Publisher path relationship is invalid");
        }
    }

    private static String rootUrl(
            Map<String, String> source, String key, boolean httpsOnly) {
        var value = bounded(source, key, 2048);
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Publisher URL setting is invalid");
        }
        var scheme = uri.getScheme();
        var rawPath = uri.getRawPath();
        if ((httpsOnly ? !"https".equals(scheme) : !("http".equals(scheme) || "https".equals(scheme)))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(rawPath == null || rawPath.isEmpty() || "/".equals(rawPath))) {
            throw new IllegalArgumentException("Publisher URL setting is invalid");
        }
        return rawPath == null || rawPath.isEmpty() ? value + "/" : value;
    }

    private static String boundedInteger(
            Map<String, String> source, String key, int minimum, int maximum) {
        var value = pattern(source, key, Pattern.compile("[1-9][0-9]{0,6}"));
        try {
            var number = Integer.parseInt(value);
            if (number < minimum || number > maximum) {
                throw new IllegalArgumentException("Publisher integer setting is invalid");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Publisher integer setting is invalid");
        }
        return value;
    }

    private static String pattern(
            Map<String, String> source, String key, Pattern pattern) {
        var value = bounded(source, key, 4096);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Publisher executor setting is invalid");
        }
        return value;
    }

    private static String bounded(Map<String, String> source, String key, int maximum) {
        var value = source.get(key);
        if (value == null
                || value.isBlank()
                || !value.equals(value.strip())
                || value.length() > maximum
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Publisher executor setting is invalid");
        }
        return value;
    }

    @Override
    public String toString() {
        return "PublicationExecutorSettings[redacted]";
    }
}
