package kr.co.rhaomi.backend.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MediaStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaStorage.class);
    private static final int COPY_BUFFER_SIZE = 16 * 1024;

    private final Path root;
    private final Path tempRoot;
    private final Path mastersRoot;
    private final long maxSourceBytes;

    public MediaStorage(MediaProperties properties) {
        root = Path.of(properties.root()).toAbsolutePath().normalize();
        tempRoot = root.resolve("temp");
        mastersRoot = root.resolve("masters");
        maxSourceBytes = properties.maxSourceBytes();
        initialize();
    }

    public MediaSourceFile copySource(InputStream inputStream) {
        if (inputStream == null) {
            throw new MediaInvalidImageException();
        }

        var temp = createTempFile(".upload");
        var digest = sha256Digest();
        long total = 0;

        try (inputStream; OutputStream output = Files.newOutputStream(
                temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            var buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                total += read;
                if (total > maxSourceBytes) {
                    throw new MediaTooLargeException();
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        } catch (MediaTooLargeException exception) {
            deleteTempQuietly(temp, "source-too-large");
            throw exception;
        } catch (IOException exception) {
            deleteTempQuietly(temp, "source-copy-failed");
            throw new MediaStorageException();
        }

        if (total == 0) {
            deleteTempQuietly(temp, "source-empty");
            throw new MediaInvalidImageException();
        }

        return new MediaSourceFile(temp, total, HexFormat.of().formatHex(digest.digest()));
    }

    public Path createTempFile(String suffix) {
        try {
            return Files.createTempFile(tempRoot, "media-", suffix);
        } catch (IOException exception) {
            throw new MediaStorageException();
        }
    }

    public String moveToMaster(Path source, UUID assetId, String extension) {
        var key = storageKey(assetId, extension);
        var target = resolveStorageKey(key);
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return key;
        } catch (IOException exception) {
            throw new MediaStorageException();
        }
    }

    public Path verifiedContent(MediaAsset asset) {
        var path = resolveStorageKey(asset.getStorageKey());
        try {
            if (!Files.isRegularFile(path) || Files.size(path) != asset.getByteSize()) {
                throw new MediaStorageException();
            }
            if (!sha256(path).equals(asset.getSha256())) {
                throw new MediaStorageException();
            }
            return path;
        } catch (IOException exception) {
            throw new MediaStorageException();
        }
    }

    public void deleteTempQuietly(Path path, String operation) {
        deleteQuietly(path, operation, null);
    }

    public void deleteMasterQuietly(String storageKey, UUID assetId, String operation) {
        Path path;
        try {
            path = resolveStorageKey(storageKey);
        } catch (MediaStorageException exception) {
            LOGGER.error("media_cleanup_failed operation={} assetId={}", operation, assetId);
            return;
        }
        deleteQuietly(path, operation, assetId);
    }

    public String sha256(Path path) {
        var digest = sha256Digest();
        try (var input = Files.newInputStream(path)) {
            var buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new MediaStorageException();
        }
    }

    private void initialize() {
        try {
            Files.createDirectories(tempRoot);
            Files.createDirectories(mastersRoot);
            if (!Files.isDirectory(tempRoot)
                    || !Files.isDirectory(mastersRoot)
                    || !Files.isWritable(tempRoot)
                    || !Files.isWritable(mastersRoot)) {
                throw new IOException("media storage unavailable");
            }
            FileStore tempStore = Files.getFileStore(tempRoot);
            FileStore mastersStore = Files.getFileStore(mastersRoot);
            if (!tempStore.equals(mastersStore)) {
                throw new IOException("media storage must share one filesystem");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Media storage initialization failed");
        }
    }

    private String storageKey(UUID assetId, String extension) {
        var id = assetId.toString();
        return "masters/" + id.substring(0, 2) + "/" + id + "." + extension;
    }

    private Path resolveStorageKey(String storageKey) {
        if (storageKey == null || !storageKey.startsWith("masters/")) {
            throw new MediaStorageException();
        }
        var resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(mastersRoot)) {
            throw new MediaStorageException();
        }
        return resolved;
    }

    private void deleteQuietly(Path path, String operation, UUID assetId) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.error("media_cleanup_failed operation={} assetId={}", operation, assetId);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
