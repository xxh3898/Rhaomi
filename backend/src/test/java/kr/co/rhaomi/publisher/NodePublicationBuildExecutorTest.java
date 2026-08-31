package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodePublicationBuildExecutorTest {

    @TempDir
    Path tempDirectory;

    @Test
    void should_mapSafeMachineResults_when_scriptReturnsTypedOutput() throws Exception {
        var published = executor(script("""
                printf '{"status":"PUBLISHED","retentionStatus":"COMPLETE","contentRevision":"9007199254740993","publishGeneration":"%s","generatedAt":"2026-08-31T00:00:00.123456Z","releaseId":"g-%s.r-1.c-bbbbbbbbbbbb"}\n' "$2" "$2"
                """));
        var publishedWithDeferredRetention = executor(script("""
                printf '{"status":"PUBLISHED","retentionStatus":"DEFERRED","contentRevision":"1","publishGeneration":"%s","generatedAt":"2026-08-31T00:00:00Z","releaseId":"g-%s.r-1.c-bbbbbbbbbbbb"}\n' "$2" "$2"
                """));
        var noop = executor(script("""
                printf '{"status":"NO_PUBLIC_CHANGE","retentionStatus":"NOT_APPLICABLE","contentRevision":"1","publishGeneration":"%s","generatedAt":"2026-08-31T00:00:00Z","releaseId":"g-%s.r-1.c-bbbbbbbbbbbb"}\n' "$2" "$2"
                """));

        assertEquals(PublicationBuildResult.SUCCESS, published.execute(9_007_199_254_740_993L));
        assertEquals(
                PublicationBuildResult.SUCCESS,
                publishedWithDeferredRetention.execute(2));
        assertEquals(PublicationBuildResult.NO_PUBLIC_CHANGE, noop.execute(Long.MAX_VALUE));
    }

    @Test
    void should_mapFailureDispositionAndRejectMalformedOrMismatchedOutput() throws Exception {
        var transientFailure = executor(script("""
                printf '{"status":"FAILED","code":"BUILD_API_TRANSIENT","disposition":"TRANSIENT"}\n' >&2
                exit 21
                """));
        var generationFailure = executor(script("""
                printf '{"status":"FAILED","code":"BUILD_GENERATION_NOT_ACTIVE","disposition":"GENERATION"}\n' >&2
                exit 22
                """));
        var malformed = executor(script("""
                printf '{"status":"PUBLISHED","contentRevision":"1","publishGeneration":"2"}\n'
                """));

        assertEquals(PublicationBuildResult.TRANSIENT_FAILURE, transientFailure.execute(1));
        assertEquals(PublicationBuildResult.TRANSIENT_FAILURE, generationFailure.execute(1));
        assertEquals(PublicationBuildResult.TERMINAL_FAILURE, malformed.execute(1));
    }

    @Test
    void should_rejectMultipleAndOversizedMachineOutput() throws Exception {
        var multiple = executor(script("""
                printf '{"status":"FAILED","code":"SAFE_FAILURE","disposition":"TERMINAL"}\n'
                printf '{"status":"FAILED","code":"SAFE_FAILURE","disposition":"TERMINAL"}\n'
                exit 20
                """));
        var oversized = executor(script("""
                count=0
                while [ "$count" -lt 17000 ]; do
                  printf x
                  count=$((count + 1))
                done
                """));

        assertEquals(PublicationBuildResult.TERMINAL_FAILURE, multiple.execute(1));
        assertEquals(PublicationBuildResult.TERMINAL_FAILURE, oversized.execute(1));
    }

    @Test
    void should_returnTransientWithoutLeakingLaunchDetail_when_executableDisappears()
            throws Exception {
        var executable = tempDirectory.resolve("disappearing-node");
        Files.copy(Path.of("/bin/sh"), executable);
        assertTrue(executable.toFile().setExecutable(true));
        var settings = new PublicationExecutorSettings(
                executable,
                Path.of("/bin/true"),
                Duration.ofMillis(150),
                PublicationExecutorSettingsTest.environment());
        Files.delete(executable);

        assertEquals(
                PublicationBuildResult.TRANSIENT_FAILURE,
                new NodePublicationBuildExecutor(settings).execute(1));
    }

    @Test
    void should_terminateObservedDescendantBeforeReturning_when_rootExitsFirst()
            throws Exception {
        var workRoot = tempDirectory.resolve("normal-exit-work");
        Files.createDirectories(workRoot);
        var script = script("""
                (
                  trap '' TERM INT
                  while :; do sleep 1; done
                ) >/dev/null 2>&1 &
                child=$!
                printf '%s' "$child" > "$RHAOMI_PUBLISHER_WORK_ROOT/orphan.pid"
                sleep 1
                printf '{"status":"PUBLISHED","retentionStatus":"COMPLETE","contentRevision":"1","publishGeneration":"%s","generatedAt":"2026-08-31T00:00:00Z","releaseId":"g-%s.r-1.c-bbbbbbbbbbbb"}\n' "$2" "$2"
                """);
        var environment = PublicationExecutorSettingsTest.environment();
        environment.put("RHAOMI_PUBLISHER_WORK_ROOT", workRoot.toString());
        var executor = new NodePublicationBuildExecutor(new PublicationExecutorSettings(
                Path.of("/bin/sh"),
                script,
                Duration.ofMillis(150),
                environment));

        assertEquals(PublicationBuildResult.TRANSIENT_FAILURE, executor.execute(8));
        var orphanPid = Long.parseLong(Files.readString(workRoot.resolve("orphan.pid")));
        assertFalse(physicallyAlive(orphanPid));
    }

    @Test
    void should_waitForRootAndInterruptIgnoringDescendantToTerminate_when_asyncTaskIsCancelled()
            throws Exception {
        var workRoot = tempDirectory.resolve("work");
        Files.createDirectories(workRoot);
        var script = script("""
                trap '' TERM INT
                (
                  trap '' TERM INT
                  while :; do sleep 1; done
                ) &
                child=$!
                printf '%s' "$child" > "$RHAOMI_PUBLISHER_WORK_ROOT/child.pid"
                while :; do sleep 1; done
                """);
        var environment = PublicationExecutorSettingsTest.environment();
        environment.put("RHAOMI_PUBLISHER_WORK_ROOT", workRoot.toString());
        var executor = new NodePublicationBuildExecutor(new PublicationExecutorSettings(
                Path.of("/bin/sh"),
                script,
                Duration.ofMillis(150),
                environment));
        var executorService = Executors.newSingleThreadExecutor();
        try {
            var factory = new AsyncPublicationBuildTaskFactory(executor, executorService);
            try (var task = factory.start(7)) {
                var pidFile = workRoot.resolve("child.pid");
                assertTrue(waitUntil(() -> Files.exists(pidFile), Duration.ofSeconds(5)));
                var childPid = Long.parseLong(Files.readString(pidFile));
                assertTrue(physicallyAlive(childPid));

                task.cancel();

                assertFalse(physicallyAlive(childPid));
            }
        } finally {
            executorService.shutdownNow();
            assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private NodePublicationBuildExecutor executor(Path script) {
        return new NodePublicationBuildExecutor(new PublicationExecutorSettings(
                Path.of("/bin/sh"),
                script,
                Duration.ofMillis(150),
                PublicationExecutorSettingsTest.environment()));
    }

    private Path script(String body) throws Exception {
        var script = Files.createTempFile(tempDirectory, "publisher-executor-", ".sh");
        Files.writeString(script, "#!/bin/sh\nset -eu\n" + body);
        return script;
    }

    private boolean waitUntil(CheckedCondition condition, Duration timeout) throws Exception {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) return true;
            Thread.sleep(20);
        }
        return condition.evaluate();
    }

    private boolean physicallyAlive(long pid) throws Exception {
        var handleAlive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        if (!handleAlive) return false;
        var statPath = Path.of("/proc", Long.toString(pid), "stat");
        if (!Files.exists(statPath)) return handleAlive;
        var stat = Files.readString(statPath);
        var commandEnd = stat.lastIndexOf(')');
        return commandEnd < 0
                || commandEnd + 2 >= stat.length()
                || stat.charAt(commandEnd + 2) != 'Z';
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}
