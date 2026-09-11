package kr.co.rhaomi.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class InitialContentBundleReaderTest {

    @TempDir
    Path tempDirectory;

    private final InitialContentBundleReader reader = new InitialContentBundleReader();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void should_parseExactOwnerBundle_when_manifestAndFilesAreCanonical() throws Exception {
        var root = InitialContentTestBundle.create(tempDirectory);

        var bundle = reader.read(root);

        assertEquals(InitialContentTestBundle.SHOP_NAME, bundle.shopSettings().shopName());
        assertEquals(1, bundle.media().size());
        assertEquals(1, bundle.breeds().size());
        assertEquals(1, bundle.services().size());
        assertEquals(1, bundle.notices().size());
        assertEquals(1, bundle.galleryItems().size());
        assertEquals(InitialContentTestBundle.sha256(root.resolve("manifest.json")), bundle.manifestSha256());
    }

    @Test
    void should_rejectBundle_when_checksumOrExtraFileDoesNotMatchManifest() throws Exception {
        var checksumRoot = InitialContentTestBundle.create(tempDirectory.resolve("checksum"));
        Files.writeString(
                checksumRoot.resolve("content.json"),
                InitialContentTestBundle.validContent() + " ",
                StandardCharsets.UTF_8);

        assertCode("INITIAL_CONTENT_CHECKSUM_INVALID", () -> reader.read(checksumRoot));

        var extraRoot = InitialContentTestBundle.create(tempDirectory.resolve("extra"));
        Files.writeString(extraRoot.resolve("media/untracked.txt"), "extra", StandardCharsets.UTF_8);

        assertCode("INITIAL_CONTENT_FILE_INVENTORY_INVALID", () -> reader.read(extraRoot));
    }

    @Test
    void should_rejectBundle_when_symlinkOrHardlinkIsPresent() throws Exception {
        var symlinkParent = Files.createDirectories(tempDirectory.resolve("symlink"));
        var symlinkRoot = InitialContentTestBundle.create(symlinkParent);
        var original = symlinkRoot.resolve("media/cover.png");
        var outside = symlinkParent.resolve("outside.png");
        Files.move(original, outside);
        Files.createSymbolicLink(original, outside);

        assertThrows(InitialContentImportException.class, () -> reader.read(symlinkRoot));

        var hardlinkParent = Files.createDirectories(tempDirectory.resolve("hardlink"));
        var hardlinkRoot = InitialContentTestBundle.create(hardlinkParent);
        var hardlinkMedia = hardlinkRoot.resolve("media/cover.png");
        var linkedOutside = hardlinkParent.resolve("outside.png");
        Files.move(hardlinkMedia, linkedOutside);
        Files.createLink(hardlinkMedia, linkedOutside);

        assertCode("INITIAL_CONTENT_LINK_INVALID", () -> reader.read(hardlinkRoot));
    }

    @Test
    void should_rejectBundle_when_pathTraversalOrDuplicateJsonKeyIsDeclared() throws Exception {
        var traversalRoot = InitialContentTestBundle.create(tempDirectory.resolve("traversal"));
        var manifest = Files.readString(traversalRoot.resolve("manifest.json"), StandardCharsets.UTF_8)
                .replace("media/cover.png", "../cover.png");
        Files.writeString(traversalRoot.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);

        assertCode("INITIAL_CONTENT_PATH_INVALID", () -> reader.read(traversalRoot));

        var duplicateRoot = InitialContentTestBundle.create(tempDirectory.resolve("duplicate"));
        var content = InitialContentTestBundle.validContent()
                .replace("\"name\": \"말티푸\"", "\"name\": \"말티푸\",\n                      \"name\": \"푸들\"");
        Files.writeString(duplicateRoot.resolve("content.json"), content, StandardCharsets.UTF_8);
        InitialContentTestBundle.rewriteManifest(duplicateRoot);

        assertThrows(InitialContentImportException.class, () -> reader.read(duplicateRoot));
    }

    @Test
    void should_rejectBundle_when_logicalKeyIsDuplicated() throws Exception {
        var root = InitialContentTestBundle.create(tempDirectory);
        var contentPath = root.resolve("content.json");
        var content = (ObjectNode) objectMapper.readTree(Files.readString(contentPath));
        var breeds = (ArrayNode) content.get("breeds");
        breeds.add(breeds.get(0).deepCopy());
        Files.writeString(contentPath, objectMapper.writeValueAsString(content));
        InitialContentTestBundle.rewriteManifest(root);

        assertCode("INITIAL_CONTENT_DUPLICATE_KEY", () -> reader.read(root));
    }

    @Test
    void should_rejectBundle_when_schemaVersionOrRequiredCategoryIsInvalid() throws Exception {
        var schemaRoot = InitialContentTestBundle.create(tempDirectory.resolve("schema"));
        var manifestPath = schemaRoot.resolve("manifest.json");
        var manifest = (ObjectNode) objectMapper.readTree(Files.readString(manifestPath));
        manifest.put("schemaVersion", 2);
        Files.writeString(manifestPath, objectMapper.writeValueAsString(manifest));

        assertCode("INITIAL_CONTENT_SCHEMA_UNSUPPORTED", () -> reader.read(schemaRoot));

        var categoryRoot = InitialContentTestBundle.create(tempDirectory.resolve("category"));
        var contentPath = categoryRoot.resolve("content.json");
        var content = (ObjectNode) objectMapper.readTree(Files.readString(contentPath));
        ((ArrayNode) content.get("notices")).removeAll();
        Files.writeString(contentPath, objectMapper.writeValueAsString(content));
        InitialContentTestBundle.rewriteManifest(categoryRoot);

        assertCode("INITIAL_CONTENT_REQUIRED_DATA_MISSING", () -> reader.read(categoryRoot));
    }

    @Test
    void should_rejectBundle_when_relationIsMissing() throws Exception {
        var root = InitialContentTestBundle.create(tempDirectory);
        var content = InitialContentTestBundle.validContent()
                .replace("\"breedKey\": \"maltipoo\"", "\"breedKey\": \"missing\"");
        Files.writeString(root.resolve("content.json"), content, StandardCharsets.UTF_8);
        InitialContentTestBundle.rewriteManifest(root);

        assertCode("INITIAL_CONTENT_RELATION_INVALID", () -> reader.read(root));
    }

    private void assertCode(String code, ThrowingAction action) {
        var exception = assertThrows(InitialContentImportException.class, action::run);
        assertEquals(code, exception.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
