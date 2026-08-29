package kr.co.rhaomi.backend.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaProcessorContractTests {

    @TempDir
    private Path tempDirectory;

    @Test
    void should_decodeAndNormalizeValidHeic_when_nativeCodecIsAvailable() throws Exception {
        var properties = properties(
                tempDirectory.resolve("valid-heic"), 20 * 1024 * 1024, 30 * 1024 * 1024);
        var storage = new MediaStorage(properties);
        var source = storage.copySource(new ByteArrayInputStream(
                MediaTestFixtures.resource("synthetic-orientation-metadata.heic")));
        var processor = new MediaImageProcessor(
                properties, storage, new MediaFormatDetector(), new HeifCodecProbe());

        var processed = processor.process(source, "image/heic", "source.heic");

        assertEquals("image/jpeg", processed.contentType());
        assertEquals(48, processed.width());
        assertEquals(64, processed.height());
        assertTrue(Files.size(processed.path()) > 32);
        storage.deleteTempQuietly(processed.path(), "test-cleanup");
        storage.deleteTempQuietly(source.path(), "test-cleanup");
    }

    @Test
    void should_failFast_when_heicReaderIsUnavailableAtStartup() {
        var probe = new HeifCodecProbe(() -> {}, Collections::emptyIterator);

        var failure = assertThrows(IllegalStateException.class, probe::verifyAvailable);

        assertEquals("HEIC processor initialization failed", failure.getMessage());
        assertInstanceOf(MediaProcessorUnavailableException.class, failure.getCause());
    }

    @Test
    void should_failFast_when_nativeCodecCannotBeLinkedAtStartup() {
        var probe = new HeifCodecProbe(
                () -> {
                    throw new UnsatisfiedLinkError("synthetic codec failure");
                },
                Collections::emptyIterator);

        var failure = assertThrows(IllegalStateException.class, probe::verifyAvailable);

        assertEquals("HEIC processor initialization failed", failure.getMessage());
        assertInstanceOf(UnsatisfiedLinkError.class, failure.getCause());
    }

    @Test
    void should_rejectAndCleanTemporaryOutput_when_normalizedJpegExceedsStoredLimit()
            throws Exception {
        var properties = properties(tempDirectory.resolve("output-limit"), 20 * 1024 * 1024, 32);
        var storage = new MediaStorage(properties);
        var source = storage.copySource(new ByteArrayInputStream(
                MediaTestFixtures.resource("synthetic-orientation-metadata.heic")));
        var processor = new MediaImageProcessor(
                properties, storage, new MediaFormatDetector(), new HeifCodecProbe());

        assertThrows(
                MediaInvalidImageException.class,
                () -> processor.process(source, "image/heic", "source.heic"));

        assertEquals(1, regularFileCount(tempDirectory.resolve("output-limit/temp")));
        storage.deleteTempQuietly(source.path(), "test-cleanup");
        assertEquals(0, regularFileCount(tempDirectory.resolve("output-limit/temp")));
    }

    @Test
    void should_acceptExactSourceLimitAndCleanPartialSource_when_streamExceedsLimit()
            throws Exception {
        var properties = properties(tempDirectory.resolve("source-limit"), 10, 30 * 1024 * 1024);
        var storage = new MediaStorage(properties);

        var exact = storage.copySource(new ByteArrayInputStream(new byte[10]));

        assertEquals(10, exact.byteSize());
        storage.deleteTempQuietly(exact.path(), "test-cleanup");

        assertThrows(
                MediaTooLargeException.class,
                () -> storage.copySource(new ByteArrayInputStream(new byte[11])));

        assertEquals(0, regularFileCount(tempDirectory.resolve("source-limit/temp")));
    }

    @Test
    void should_failFast_when_storageRootIsNotDirectory() throws Exception {
        var rootFile = Files.createFile(tempDirectory.resolve("not-a-directory"));

        var failure = assertThrows(
                IllegalStateException.class,
                () -> new MediaStorage(properties(rootFile, 20 * 1024 * 1024, 30 * 1024 * 1024)));

        assertEquals("Media storage initialization failed", failure.getMessage());
    }

    private MediaProperties properties(Path root, long maxSourceBytes, long maxStoredBytes) {
        return new MediaProperties(
                root.toString(),
                maxSourceBytes,
                maxStoredBytes,
                12000,
                12000,
                60000000,
                92);
    }

    private long regularFileCount(Path root) throws Exception {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }
}
