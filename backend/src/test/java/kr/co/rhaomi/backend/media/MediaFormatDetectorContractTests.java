package kr.co.rhaomi.backend.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaFormatDetectorContractTests {

    @TempDir
    private Path tempDirectory;

    private final MediaFormatDetector detector = new MediaFormatDetector();

    @Test
    void should_recognizeHeicStill_when_majorBrandIsHeic() throws Exception {
        assertEquals(MediaSourceType.HEIC, detect("heic"));
    }

    @Test
    void should_recognizeHeicStill_when_majorBrandIsHeix() throws Exception {
        assertEquals(MediaSourceType.HEIC, detect("heix"));
    }

    @Test
    void should_recognizeHeicStill_when_majorBrandIsHeim() throws Exception {
        assertEquals(MediaSourceType.HEIC, detect("heim"));
    }

    @Test
    void should_recognizeHeicStill_when_majorBrandIsHeis() throws Exception {
        assertEquals(MediaSourceType.HEIC, detect("heis"));
    }

    @Test
    void should_recognizeHeicStill_when_compatibleBrandIsHeic() throws Exception {
        assertCompatibleHeic("heic");
    }

    @Test
    void should_recognizeHeicStill_when_compatibleBrandIsHeix() throws Exception {
        assertCompatibleHeic("heix");
    }

    @Test
    void should_recognizeHeicStill_when_compatibleBrandIsHeim() throws Exception {
        assertCompatibleHeic("heim");
    }

    @Test
    void should_recognizeHeicStill_when_compatibleBrandIsHeis() throws Exception {
        assertCompatibleHeic("heis");
    }

    @Test
    void should_rejectSequenceAsInvalidImage_when_majorBrandIsHevc() throws Exception {
        assertInvalidSequence("hevc");
    }

    @Test
    void should_rejectSequenceAsInvalidImage_when_majorBrandIsHevx() throws Exception {
        assertInvalidSequence("hevx");
    }

    @Test
    void should_rejectSequenceAsInvalidImage_when_majorBrandIsHevm() throws Exception {
        assertInvalidSequence("hevm");
    }

    @Test
    void should_rejectSequenceAsInvalidImage_when_majorBrandIsHevs() throws Exception {
        assertInvalidSequence("hevs");
    }

    @Test
    void should_rejectSequenceAsInvalidImage_when_majorBrandIsMsf1() throws Exception {
        assertInvalidSequence("msf1");
    }

    @Test
    void should_rejectAvifAsUnsupported_when_majorBrandIsAvif() throws Exception {
        assertUnsupported("avif");
    }

    @Test
    void should_rejectAvifAsUnsupported_when_majorBrandIsAvis() throws Exception {
        assertUnsupported("avis");
    }

    @Test
    void should_rejectSequenceAsInvalidImage_when_stillAndSequenceBrandsAreCompatible()
            throws Exception {
        var path = write("mif1", "mif1", "heic", "hevc");

        assertThrows(MediaInvalidImageException.class, () -> detector.detect(path));
    }

    @Test
    void should_rejectAvifAsUnsupported_when_stillAndAvifBrandsAreCompatible()
            throws Exception {
        var path = write("mif1", "mif1", "heic", "avis");

        assertThrows(MediaTypeUnsupportedException.class, () -> detector.detect(path));
    }

    @Test
    void should_recognizeGenericHeif_when_majorAndCompatibleBrandsAreMif1() throws Exception {
        assertEquals(MediaSourceType.HEIF, detector.detect(write("mif1", "mif1")));
    }

    private MediaSourceType detect(String majorBrand) throws Exception {
        return detector.detect(write(majorBrand, "mif1", majorBrand));
    }

    private void assertInvalidSequence(String brand) throws Exception {
        var path = write(brand, "mif1", brand);

        assertThrows(MediaInvalidImageException.class, () -> detector.detect(path));
    }

    private void assertUnsupported(String brand) throws Exception {
        var path = write(brand, "mif1", brand);

        assertThrows(MediaTypeUnsupportedException.class, () -> detector.detect(path));
    }

    private void assertCompatibleHeic(String brand) throws Exception {
        assertEquals(MediaSourceType.HEIC, detector.detect(write("mif1", "mif1", brand)));
    }

    private Path write(String majorBrand, String... compatibleBrands) throws Exception {
        return Files.write(
                tempDirectory.resolve(majorBrand + ".bin"),
                MediaTestFixtures.isoBmffHeader(majorBrand, compatibleBrands));
    }
}
