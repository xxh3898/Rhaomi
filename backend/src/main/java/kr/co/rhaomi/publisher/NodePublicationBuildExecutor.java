package kr.co.rhaomi.publisher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class NodePublicationBuildExecutor implements PublicationBuildExecutor {

    private static final int MAX_OUTPUT_BYTES = 16 * 1024;
    private static final Pattern NON_NEGATIVE_LONG = Pattern.compile("(?:0|[1-9][0-9]*)");
    private static final Pattern POSITIVE_LONG = Pattern.compile("[1-9][0-9]*");
    private static final Pattern RELEASE_ID = Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");
    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,127}");
    private static final Set<String> SUCCESS_FIELDS = Set.of(
            "status",
            "retentionStatus",
            "contentRevision",
            "publishGeneration",
            "generatedAt",
            "releaseId");
    private static final Set<String> FAILURE_FIELDS =
            Set.of("status", "code", "disposition");

    private final PublicationExecutorSettings settings;
    private final ObjectMapper objectMapper;

    NodePublicationBuildExecutor(PublicationExecutorSettings settings) {
        this(settings, new ObjectMapper());
    }

    NodePublicationBuildExecutor(
            PublicationExecutorSettings settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    @Override
    public PublicationBuildResult execute(long targetGeneration) {
        if (targetGeneration <= 0) return PublicationBuildResult.TERMINAL_FAILURE;
        var processBuilder = new ProcessBuilder(
                settings.nodeExecutable().toString(),
                settings.releaseScript().toString(),
                "--publish-generation",
                Long.toString(targetGeneration));
        processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE);
        var childEnvironment = processBuilder.environment();
        childEnvironment.clear();
        childEnvironment.putAll(settings.environment());

        final Process process;
        try {
            process = processBuilder.start();
            process.getOutputStream().close();
        } catch (IOException exception) {
            return PublicationBuildResult.TRANSIENT_FAILURE;
        }

        var stdout = new BoundedOutput(process.getInputStream());
        var stderr = new BoundedOutput(process.getErrorStream());
        var stdoutThread = Thread.startVirtualThread(stdout);
        var stderrThread = Thread.startVirtualThread(stderr);
        var knownProcesses = new ConcurrentHashMap<Long, ProcessHandle>();
        var processTracker = Thread.startVirtualThread(
                () -> trackProcessTree(process.toHandle(), knownProcesses));
        final int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException exception) {
            terminateProcessTree(process, knownProcesses, settings.terminationGrace());
            awaitThreads(processTracker);
            awaitOutput(stdoutThread, stderrThread);
            Thread.currentThread().interrupt();
            return PublicationBuildResult.TRANSIENT_FAILURE;
        }
        awaitThreads(processTracker);
        if (knownProcesses.values().stream().anyMatch(this::physicallyAlive)) {
            terminateProcessTree(process, knownProcesses, settings.terminationGrace());
            awaitOutput(stdoutThread, stderrThread);
            return PublicationBuildResult.TRANSIENT_FAILURE;
        }
        awaitOutput(stdoutThread, stderrThread);
        if (stdout.overflowed() || stderr.overflowed() || stdout.failed() || stderr.failed()) {
            return PublicationBuildResult.TERMINAL_FAILURE;
        }
        return parseResult(
                exitCode,
                stdout.utf8(),
                stderr.utf8(),
                Long.toString(targetGeneration));
    }

    private PublicationBuildResult parseResult(
            int exitCode, String stdout, String stderr, String targetGeneration) {
        if (exitCode == 0) {
            if (!stderr.isBlank()) return PublicationBuildResult.TERMINAL_FAILURE;
            var node = parseSingleObject(stdout, SUCCESS_FIELDS);
            if (node == null) return PublicationBuildResult.TERMINAL_FAILURE;
            var status = text(node, "status");
            var retentionStatus = text(node, "retentionStatus");
            var contentRevision = text(node, "contentRevision");
            var publishGeneration = text(node, "publishGeneration");
            var generatedAt = text(node, "generatedAt");
            var releaseId = text(node, "releaseId");
            if (!canonicalLong(contentRevision, false)
                    || !canonicalLong(publishGeneration, true)
                    || !targetGeneration.equals(publishGeneration)
                    || !validInstant(generatedAt)
                    || releaseId == null
                    || !RELEASE_ID.matcher(releaseId).matches()) {
                return PublicationBuildResult.TERMINAL_FAILURE;
            }
            return switch (status == null ? "" : status) {
                case "PUBLISHED" -> "COMPLETE".equals(retentionStatus)
                                || "DEFERRED".equals(retentionStatus)
                        ? PublicationBuildResult.SUCCESS
                        : PublicationBuildResult.TERMINAL_FAILURE;
                case "NO_PUBLIC_CHANGE" -> "NOT_APPLICABLE".equals(retentionStatus)
                        ? PublicationBuildResult.NO_PUBLIC_CHANGE
                        : PublicationBuildResult.TERMINAL_FAILURE;
                default -> PublicationBuildResult.TERMINAL_FAILURE;
            };
        }

        if (!stdout.isBlank()) return PublicationBuildResult.TERMINAL_FAILURE;
        var node = parseSingleObject(stderr, FAILURE_FIELDS);
        if (node == null
                || !"FAILED".equals(text(node, "status"))
                || text(node, "code") == null
                || !SAFE_ERROR_CODE.matcher(text(node, "code")).matches()) {
            return PublicationBuildResult.TERMINAL_FAILURE;
        }
        var disposition = text(node, "disposition");
        return switch (exitCode) {
            case 20 -> PublicationBuildResult.TERMINAL_FAILURE;
            case 21 -> "TRANSIENT".equals(disposition)
                    ? PublicationBuildResult.TRANSIENT_FAILURE
                    : PublicationBuildResult.TERMINAL_FAILURE;
            case 22 -> "GENERATION".equals(disposition)
                    ? PublicationBuildResult.TRANSIENT_FAILURE
                    : PublicationBuildResult.TERMINAL_FAILURE;
            default -> PublicationBuildResult.TERMINAL_FAILURE;
        };
    }

    private JsonNode parseSingleObject(String output, Set<String> expectedFields) {
        var value = output.strip();
        if (value.isEmpty() || value.lines().count() != 1) return null;
        try {
            var node = objectMapper.readTree(value);
            if (node == null || !node.isObject()) return null;
            var fields = new LinkedHashSet<>(node.propertyNames());
            return fields.equals(expectedFields) ? node : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isString() ? value.asText() : null;
    }

    private boolean canonicalLong(String value, boolean positive) {
        if (value == null
                || !(positive ? POSITIVE_LONG : NON_NEGATIVE_LONG).matcher(value).matches()) {
            return false;
        }
        try {
            var parsed = Long.parseLong(value);
            return positive ? parsed > 0 : parsed >= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean validInstant(String value) {
        if (value == null) return false;
        try {
            Instant.parse(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void awaitOutput(Thread... threads) {
        awaitThreads(threads);
    }

    private void awaitThreads(Thread... threads) {
        var interrupted = false;
        for (var thread : threads) {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void trackProcessTree(
            ProcessHandle root, Map<Long, ProcessHandle> known) {
        captureTree(root, known);
        while (root.isAlive()) {
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException exception) {
                // Physical process lifetime, not thread interruption, ends tracking.
            }
            captureTree(root, known);
        }
        captureTree(root, known);
    }

    private void terminateProcessTree(
            Process process,
            Map<Long, ProcessHandle> known,
            Duration gracefulWait) {
        var root = process.toHandle();
        captureTree(root, known);
        signal(root, known, false);
        awaitTermination(root, known, gracefulWait);
        captureTree(root, known);
        signal(root, known, true);

        var interrupted = false;
        while (known.values().stream().anyMatch(this::physicallyAlive)) {
            captureTree(root, known);
            signal(root, known, true);
            try {
                TimeUnit.MILLISECONDS.sleep(25);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void awaitTermination(
            ProcessHandle root,
            Map<Long, ProcessHandle> known,
            Duration gracefulWait) {
        var deadline = System.nanoTime() + gracefulWait.toNanos();
        while (System.nanoTime() < deadline
                && known.values().stream().anyMatch(this::physicallyAlive)) {
            captureTree(root, known);
            try {
                TimeUnit.MILLISECONDS.sleep(25);
            } catch (InterruptedException exception) {
                // Cancellation is already in progress; physical termination remains authoritative.
            }
        }
    }

    private void captureTree(
            ProcessHandle root, Map<Long, ProcessHandle> known) {
        try {
            root.descendants().forEach(handle -> known.put(handle.pid(), handle));
        } catch (RuntimeException exception) {
            // Known descendants and the root remain the fail-closed termination set.
        }
        known.put(root.pid(), root);
    }

    private void signal(
            ProcessHandle root,
            Map<Long, ProcessHandle> known,
            boolean force) {
        var handles = new ArrayList<>(known.values());
        handles.removeIf(handle -> handle.pid() == root.pid());
        handles.add(root);
        for (var handle : handles) {
            if (!physicallyAlive(handle)) continue;
            try {
                if (force) handle.destroyForcibly();
                else handle.destroy();
            } catch (RuntimeException exception) {
                // The next physical liveness pass retries until every known process is gone.
            }
        }
    }

    private boolean physicallyAlive(ProcessHandle handle) {
        if (!handle.isAlive()) return false;
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return true;
        }
        try {
            var stat = Files.readString(Path.of("/proc", Long.toString(handle.pid()), "stat"));
            var commandEnd = stat.lastIndexOf(')');
            return commandEnd < 0
                    || commandEnd + 2 >= stat.length()
                    || stat.charAt(commandEnd + 2) != 'Z';
        } catch (IOException exception) {
            return handle.isAlive();
        }
    }

    private static final class BoundedOutput implements Runnable {

        private final InputStream input;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean overflowed;
        private volatile boolean failed;

        private BoundedOutput(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            var buffer = new byte[1024];
            try (input) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (bytes.size() + read > MAX_OUTPUT_BYTES) {
                        var remaining = Math.max(0, MAX_OUTPUT_BYTES - bytes.size());
                        bytes.write(buffer, 0, remaining);
                        overflowed = true;
                    } else {
                        bytes.write(buffer, 0, read);
                    }
                }
            } catch (IOException exception) {
                failed = true;
            }
        }

        private boolean overflowed() {
            return overflowed;
        }

        private boolean failed() {
            return failed;
        }

        private String utf8() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
