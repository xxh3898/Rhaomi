package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemPublicationExecutionLockTest {

    @TempDir
    Path tempDirectory;

    @Test
    void should_allowOnlyOneHolderAndWriteNoContent_when_sameFileIsContended()
            throws Exception {
        var lockFile = tempDirectory.resolve("publisher.lock");
        var firstLock = new FileSystemPublicationExecutionLock(lockFile);
        var secondLock = new FileSystemPublicationExecutionLock(lockFile);

        var first = firstLock.tryAcquire().orElseThrow();
        try {
            assertTrue(Files.exists(lockFile));
            assertEquals(0L, Files.size(lockFile));
            assertTrue(secondLock.tryAcquire().isEmpty());
        } finally {
            first.close();
        }

        var second = secondLock.tryAcquire().orElseThrow();
        second.close();
        assertEquals(0L, Files.size(lockFile));
    }

    @Test
    void should_returnFixedFailureWithoutPathDetail_when_lockParentIsNotDirectory()
            throws Exception {
        var parentFile = tempDirectory.resolve("not-a-directory");
        Files.writeString(parentFile, "fixture");
        var lock = new FileSystemPublicationExecutionLock(parentFile.resolve("publisher.lock"));

        var exception = assertThrows(
                FileSystemPublicationExecutionLock.PublicationLockException.class,
                lock::tryAcquire);

        assertEquals("Publication lock unavailable", exception.getMessage());
        assertFalse(exception.getMessage().contains(tempDirectory.toString()));
        assertEquals(null, exception.getCause());
    }
}
