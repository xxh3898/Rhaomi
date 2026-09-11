package kr.co.rhaomi.production;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

final class InitialContentImportRunner implements ApplicationRunner {

    private static final Pattern FAILURE_CODE = Pattern.compile("INITIAL_CONTENT_[A-Z0-9_]+");
    private static final String GENERIC_FAILURE_CODE = "INITIAL_CONTENT_IMPORT_FAILED";

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
            writeFailure(exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            writeFailure(GENERIC_FAILURE_CODE);
            throw new InitialContentImportException(GENERIC_FAILURE_CODE);
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

    private void writeFailure(String code) {
        var safeCode = code != null && FAILURE_CODE.matcher(code).matches()
                ? code
                : GENERIC_FAILURE_CODE;
        output.printf(
                "{\"contract\":\"rhaomi-initial-content-v1\","
                        + "\"status\":\"failure\",\"code\":\"%s\"}%n",
                safeCode);
        output.flush();
    }
}
