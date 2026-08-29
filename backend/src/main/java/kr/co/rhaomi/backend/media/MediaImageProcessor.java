package kr.co.rhaomi.backend.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import org.springframework.stereotype.Component;

@Component
public class MediaImageProcessor {

    private final MediaProperties properties;
    private final MediaStorage storage;
    private final MediaFormatDetector formatDetector;

    public MediaImageProcessor(
            MediaProperties properties,
            MediaStorage storage,
            MediaFormatDetector formatDetector,
            HeifCodecProbe codecProbe) {
        this.properties = properties;
        this.storage = storage;
        this.formatDetector = formatDetector;
        codecProbe.verifyAvailable();
    }

    public ProcessedMedia process(
            MediaSourceFile source, String declaredContentType, String originalFilename) {
        var sourceType = formatDetector.detect(source.path());
        formatDetector.validateDeclaredMetadata(sourceType, declaredContentType, originalFilename);
        var inspection = inspect(source.path());

        if (!sourceType.isHeifFamily()) {
            verifyStoredSize(source.byteSize());
            return new ProcessedMedia(
                    source.path(),
                    sourceType.sourceContentType(),
                    sourceType.storedContentType(),
                    sourceType.storedExtension(),
                    source.byteSize(),
                    source.byteSize(),
                    inspection.width(),
                    inspection.height(),
                    source.sha256());
        }

        var normalized = storage.createTempFile(".jpg");
        try {
            writeJpeg(inspection.image(), normalized);
            var storedSize = Files.size(normalized);
            verifyStoredSize(storedSize);
            if (formatDetector.detect(normalized) != MediaSourceType.JPEG) {
                throw new MediaInvalidImageException();
            }
            var storedInspection = inspect(normalized);
            if (storedInspection.width() != inspection.width()
                    || storedInspection.height() != inspection.height()) {
                throw new MediaInvalidImageException();
            }
            return new ProcessedMedia(
                    normalized,
                    sourceType.sourceContentType(),
                    MediaSourceType.JPEG.storedContentType(),
                    MediaSourceType.JPEG.storedExtension(),
                    source.byteSize(),
                    storedSize,
                    storedInspection.width(),
                    storedInspection.height(),
                    storage.sha256(normalized));
        } catch (MediaInvalidImageException | MediaTooLargeException | MediaStorageException exception) {
            storage.deleteTempQuietly(normalized, "normalization-failed");
            throw exception;
        } catch (IOException exception) {
            storage.deleteTempQuietly(normalized, "normalization-io-failed");
            throw new MediaStorageException();
        } catch (LinkageError exception) {
            storage.deleteTempQuietly(normalized, "normalization-codec-failed");
            throw new MediaProcessorUnavailableException();
        }
    }

    private ImageInspection inspect(Path path) {
        try (var input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) {
                throw new MediaInvalidImageException();
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new MediaInvalidImageException();
            }
            var reader = readers.next();
            try {
                reader.setInput(input, false, true);
                var count = reader.getNumImages(true);
                if (count != 1) {
                    throw new MediaInvalidImageException();
                }
                var width = reader.getWidth(0);
                var height = reader.getHeight(0);
                validateDimensions(width, height);
                var image = reader.read(0);
                if (image == null || image.getWidth() != width || image.getHeight() != height) {
                    throw new MediaInvalidImageException();
                }
                return new ImageInspection(width, height, image);
            } finally {
                reader.dispose();
            }
        } catch (MediaInvalidImageException | MediaProcessorUnavailableException exception) {
            throw exception;
        } catch (LinkageError exception) {
            throw new MediaProcessorUnavailableException();
        } catch (IOException | RuntimeException exception) {
            throw new MediaInvalidImageException(exception);
        }
    }

    private void validateDimensions(int width, int height) {
        if (width <= 0
                || height <= 0
                || width > properties.maxWidth()
                || height > properties.maxHeight()
                || (long) width * height > properties.maxPixels()) {
            throw new MediaInvalidImageException();
        }
    }

    private void verifyStoredSize(long size) {
        if (size <= 0) {
            throw new MediaInvalidImageException();
        }
        if (size > properties.maxStoredBytes()) {
            throw new MediaInvalidImageException();
        }
    }

    private void writeJpeg(BufferedImage source, Path target) throws IOException {
        var rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        var writers = ImageIO.getImageWritersByFormatName("JPEG");
        if (!writers.hasNext()) {
            throw new MediaProcessorUnavailableException();
        }
        var writer = writers.next();
        try (var output = ImageIO.createImageOutputStream(target.toFile())) {
            if (output == null) {
                throw new MediaStorageException();
            }
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(properties.jpegQuality() / 100.0f);
            writer.write(null, new IIOImage(rgb, null, null), parameters);
        } finally {
            writer.dispose();
        }
    }

    private record ImageInspection(int width, int height, BufferedImage image) {}
}
