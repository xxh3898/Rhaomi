package kr.co.rhaomi.publisher;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

public final class FileSystemPublicationExecutionLock implements PublicationExecutionLock {

    private final Path lockFile;

    public FileSystemPublicationExecutionLock(Path lockFile) {
        this.lockFile = lockFile.toAbsolutePath().normalize();
    }

    @Override
    public Optional<Handle> tryAcquire() {
        FileChannel channel = null;
        try {
            Files.createDirectories(lockFile.getParent());
            channel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            var fileLock = tryFileLock(channel);
            if (fileLock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new FileHandle(channel, fileLock));
        } catch (IOException exception) {
            closeQuietly(channel);
            throw new PublicationLockException();
        }
    }

    private FileLock tryFileLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException exception) {
            return null;
        }
    }

    private void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // The caller receives a fixed lock failure category.
        }
    }

    static final class PublicationLockException extends RuntimeException {

        PublicationLockException() {
            super("Publication lock unavailable");
        }
    }

    private static final class FileHandle implements Handle {

        private final FileChannel channel;
        private final FileLock fileLock;

        private FileHandle(FileChannel channel, FileLock fileLock) {
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
            var failed = false;
            try {
                fileLock.release();
            } catch (IOException exception) {
                failed = true;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                failed = true;
            }
            if (failed) {
                throw new PublicationLockException();
            }
        }
    }
}
