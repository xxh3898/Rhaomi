package kr.co.rhaomi.backend.media;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.util.Iterator;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
class HeifCodecProbe {

    private final Runnable pluginScanner;
    private final Supplier<Iterator<ImageReader>> readers;

    HeifCodecProbe() {
        this(ImageIO::scanForPlugins, () -> ImageIO.getImageReadersByFormatName("HEIC"));
    }

    HeifCodecProbe(Runnable pluginScanner, Supplier<Iterator<ImageReader>> readers) {
        this.pluginScanner = pluginScanner;
        this.readers = readers;
    }

    void verifyAvailable() {
        try {
            pluginScanner.run();
            var availableReaders = readers.get();
            if (!availableReaders.hasNext()) {
                throw new MediaProcessorUnavailableException();
            }
            availableReaders.next().dispose();
        } catch (LinkageError | RuntimeException exception) {
            throw new IllegalStateException("HEIC processor initialization failed", exception);
        }
    }
}
