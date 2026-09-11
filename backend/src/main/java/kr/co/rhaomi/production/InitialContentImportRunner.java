package kr.co.rhaomi.production;

import java.io.PrintStream;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

final class InitialContentImportRunner implements ApplicationRunner {

    private final Path inputRoot;
    private final InitialContentBundleReader bundleReader;
    private final InitialContentImportService importService;
    private final PrintStream output;

    InitialContentImportRunner(
            Path inputRoot,
            InitialContentBundleReader bundleReader,
            InitialContentImportService importService,
            PrintStream output) {
        this.inputRoot = inputRoot;
        this.bundleReader = bundleReader;
        this.importService = importService;
        this.output = output;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        final InitialContentImportResult result;
        try {
            result = importService.importBundle(bundleReader.read(inputRoot));
        } catch (InitialContentImportException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InitialContentImportException("INITIAL_CONTENT_IMPORT_FAILED");
        }
        output.printf(
                "{\"contract\":\"rhaomi-initial-content-v1\","
                        + "\"status\":\"success\","
                        + "\"manifestSha256\":\"%s\","
                        + "\"contentRevision\":\"%d\","
                        + "\"publicationStatus\":\"PENDING\","
                        + "\"counts\":{\"media\":%d,\"breeds\":%d,\"services\":%d,"
                        + "\"notices\":%d,\"galleryItems\":%d}}%n",
                result.manifestSha256(),
                result.contentRevision(),
                result.mediaCount(),
                result.breedCount(),
                result.serviceCount(),
                result.noticeCount(),
                result.galleryItemCount());
        output.flush();
    }
}
