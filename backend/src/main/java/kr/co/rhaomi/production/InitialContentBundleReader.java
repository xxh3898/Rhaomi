package kr.co.rhaomi.production;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class InitialContentBundleReader {

    static final int SCHEMA_VERSION = 1;
    static final String MANIFEST_FILE = "manifest.json";
    static final String CONTENT_FILE = "content.json";
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MEDIA_PATH =
            Pattern.compile("media/[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern CONTENT_TYPE =
            Pattern.compile("image/(?:jpeg|png|heic|heif)");
    private static final Pattern UTC_MICROSECOND_INSTANT = Pattern.compile(
            "[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T"
                    + "(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
                    + "(?:\\.[0-9]{1,6})?Z");

    private static final Set<String> MANIFEST_FIELDS =
            Set.of("schemaVersion", "content", "media", "counts");
    private static final Set<String> CONTENT_DESCRIPTOR_FIELDS =
            Set.of("path", "byteSize", "sha256");
    private static final Set<String> MEDIA_DESCRIPTOR_FIELDS =
            Set.of("key", "path", "contentType", "byteSize", "sha256");
    private static final Set<String> COUNT_FIELDS =
            Set.of("shopSettings", "media", "breeds", "services", "notices", "galleryItems");
    private static final Set<String> CONTENT_FIELDS =
            Set.of("schemaVersion", "shopSettings", "breeds", "services", "notices", "galleryItems");
    private static final Set<String> SHOP_FIELDS = Set.of(
            "shopName",
            "regionLabel",
            "businessType",
            "phone",
            "address",
            "openingTime",
            "closingTime",
            "closedWeekday",
            "parkingAvailable",
            "parkingNote",
            "heroTitle",
            "heroDescription",
            "groomerName",
            "groomerIntro",
            "reservationNotice",
            "heroMediaKey",
            "heroImageAltText",
            "groomerMediaKey",
            "groomerImageAltText",
            "ogMediaKey",
            "instagramUrl",
            "naverBlogUrl",
            "naverMapUrl",
            "kakaoMapUrl",
            "naverTalktalkUrl",
            "kakaoChannelUrl");
    private static final Set<String> BREED_FIELDS =
            Set.of("key", "name", "slug", "description", "sortOrder");
    private static final Set<String> SERVICE_FIELDS =
            Set.of("key", "name", "slug", "description", "priceText", "sortOrder");
    private static final Set<String> NOTICE_FIELDS = Set.of(
            "key",
            "title",
            "slug",
            "summary",
            "bodyMarkdown",
            "pinned",
            "publishedAt",
            "expiresAt");
    private static final Set<String> GALLERY_FIELDS = Set.of(
            "key",
            "dogName",
            "breedKey",
            "primaryServiceKey",
            "coverMediaKey",
            "beforeMediaKey",
            "afterMediaKey",
            "summary",
            "altText",
            "featured",
            "sortOrder",
            "performedAt",
            "publishedAt");

    private final ObjectMapper objectMapper;

    InitialContentBundleReader() {
        objectMapper = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    InitialContentBundle read(Path inputRoot) {
        try {
            var root = requireBundleRoot(inputRoot);
            var inventory = inventory(root);
            var manifestPath = root.resolve(MANIFEST_FILE);
            requireInventoryContains(inventory, MANIFEST_FILE);
            var manifest = parse(manifestPath);
            requireFields(manifest, MANIFEST_FIELDS);
            requireSchemaVersion(manifest);

            var contentDescriptor = object(manifest, "content", CONTENT_DESCRIPTOR_FIELDS);
            var contentPath = requiredText(contentDescriptor, "path");
            if (!CONTENT_FILE.equals(contentPath)) {
                fail("INITIAL_CONTENT_MANIFEST_INVALID");
            }
            var contentByteSize = positiveLong(contentDescriptor, "byteSize");
            var contentSha = sha256Text(contentDescriptor, "sha256");

            var mediaDescriptors = array(manifest, "media");
            var media = new ArrayList<InitialContentBundle.MediaFile>(mediaDescriptors.size());
            var expectedFiles = new LinkedHashSet<String>();
            expectedFiles.add(MANIFEST_FILE);
            expectedFiles.add(CONTENT_FILE);
            var mediaKeys = new HashSet<String>();
            for (var descriptor : mediaDescriptors) {
                requireFields(descriptor, MEDIA_DESCRIPTOR_FIELDS);
                var key = logicalKey(descriptor, "key");
                if (!mediaKeys.add(key)) {
                    fail("INITIAL_CONTENT_DUPLICATE_KEY");
                }
                var relativePath = requiredText(descriptor, "path");
                if (!MEDIA_PATH.matcher(relativePath).matches() || !expectedFiles.add(relativePath)) {
                    fail("INITIAL_CONTENT_PATH_INVALID");
                }
                var resolved = resolveDirectFile(root, relativePath);
                media.add(new InitialContentBundle.MediaFile(
                        key,
                        resolved,
                        matchingText(descriptor, "contentType", CONTENT_TYPE),
                        positiveLong(descriptor, "byteSize"),
                        sha256Text(descriptor, "sha256")));
            }

            if (!inventory.equals(expectedFiles)) {
                fail("INITIAL_CONTENT_FILE_INVENTORY_INVALID");
            }
            verifyFile(root.resolve(CONTENT_FILE), contentByteSize, contentSha);
            for (var item : media) {
                verifyFile(item.path(), item.byteSize(), item.sha256());
            }

            var content = parse(root.resolve(CONTENT_FILE));
            requireFields(content, CONTENT_FIELDS);
            requireSchemaVersion(content);
            var counts = object(manifest, "counts", COUNT_FIELDS);
            if (requiredInt(counts, "shopSettings") != 1) {
                fail("INITIAL_CONTENT_COUNT_INVALID");
            }

            var shop = parseShop(object(content, "shopSettings", SHOP_FIELDS));
            var breeds = parseBreeds(array(content, "breeds"));
            var services = parseServices(array(content, "services"));
            var notices = parseNotices(array(content, "notices"));
            var galleryItems = parseGalleryItems(array(content, "galleryItems"));
            requireNonEmpty(media, breeds, services, notices, galleryItems);
            verifyCounts(counts, media, breeds, services, notices, galleryItems);
            verifyReferences(shop, mediaKeys, breeds, services, galleryItems);

            return new InitialContentBundle(
                    sha256(manifestPath),
                    shop,
                    List.copyOf(media),
                    breeds,
                    services,
                    notices,
                    galleryItems);
        } catch (InitialContentImportException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new InitialContentImportException("INITIAL_CONTENT_BUNDLE_INVALID");
        }
    }

    private InitialContentBundle.Shop parseShop(JsonNode node) {
        return new InitialContentBundle.Shop(
                requiredText(node, "shopName"),
                requiredText(node, "regionLabel"),
                requiredText(node, "businessType"),
                requiredText(node, "phone"),
                requiredText(node, "address"),
                requiredText(node, "openingTime"),
                requiredText(node, "closingTime"),
                optionalText(node, "closedWeekday"),
                requiredBoolean(node, "parkingAvailable"),
                optionalText(node, "parkingNote"),
                optionalText(node, "heroTitle"),
                optionalText(node, "heroDescription"),
                optionalText(node, "groomerName"),
                optionalText(node, "groomerIntro"),
                optionalText(node, "reservationNotice"),
                optionalKey(node, "heroMediaKey"),
                optionalText(node, "heroImageAltText"),
                optionalKey(node, "groomerMediaKey"),
                optionalText(node, "groomerImageAltText"),
                optionalKey(node, "ogMediaKey"),
                optionalText(node, "instagramUrl"),
                optionalText(node, "naverBlogUrl"),
                optionalText(node, "naverMapUrl"),
                optionalText(node, "kakaoMapUrl"),
                optionalText(node, "naverTalktalkUrl"),
                optionalText(node, "kakaoChannelUrl"));
    }

    private List<InitialContentBundle.Breed> parseBreeds(JsonNode array) {
        var items = new ArrayList<InitialContentBundle.Breed>(array.size());
        var keys = new HashSet<String>();
        for (var node : array) {
            requireFields(node, BREED_FIELDS);
            var key = uniqueKey(node, keys);
            items.add(new InitialContentBundle.Breed(
                    key,
                    requiredText(node, "name"),
                    requiredText(node, "slug"),
                    optionalText(node, "description"),
                    requiredInt(node, "sortOrder")));
        }
        return List.copyOf(items);
    }

    private List<InitialContentBundle.Service> parseServices(JsonNode array) {
        var items = new ArrayList<InitialContentBundle.Service>(array.size());
        var keys = new HashSet<String>();
        for (var node : array) {
            requireFields(node, SERVICE_FIELDS);
            var key = uniqueKey(node, keys);
            items.add(new InitialContentBundle.Service(
                    key,
                    requiredText(node, "name"),
                    requiredText(node, "slug"),
                    optionalText(node, "description"),
                    optionalText(node, "priceText"),
                    requiredInt(node, "sortOrder")));
        }
        return List.copyOf(items);
    }

    private List<InitialContentBundle.Notice> parseNotices(JsonNode array) {
        var items = new ArrayList<InitialContentBundle.Notice>(array.size());
        var keys = new HashSet<String>();
        for (var node : array) {
            requireFields(node, NOTICE_FIELDS);
            var key = uniqueKey(node, keys);
            items.add(new InitialContentBundle.Notice(
                    key,
                    requiredText(node, "title"),
                    requiredText(node, "slug"),
                    optionalText(node, "summary"),
                    optionalText(node, "bodyMarkdown"),
                    requiredBoolean(node, "pinned"),
                    requiredInstant(node, "publishedAt"),
                    optionalInstant(node, "expiresAt")));
        }
        return List.copyOf(items);
    }

    private List<InitialContentBundle.GalleryItem> parseGalleryItems(JsonNode array) {
        var items = new ArrayList<InitialContentBundle.GalleryItem>(array.size());
        var keys = new HashSet<String>();
        for (var node : array) {
            requireFields(node, GALLERY_FIELDS);
            var key = uniqueKey(node, keys);
            items.add(new InitialContentBundle.GalleryItem(
                    key,
                    optionalText(node, "dogName"),
                    logicalKey(node, "breedKey"),
                    logicalKey(node, "primaryServiceKey"),
                    logicalKey(node, "coverMediaKey"),
                    optionalKey(node, "beforeMediaKey"),
                    optionalKey(node, "afterMediaKey"),
                    optionalText(node, "summary"),
                    optionalText(node, "altText"),
                    requiredBoolean(node, "featured"),
                    requiredInt(node, "sortOrder"),
                    optionalInstant(node, "performedAt"),
                    requiredInstant(node, "publishedAt")));
        }
        return List.copyOf(items);
    }

    @SafeVarargs
    private final void requireNonEmpty(List<?>... lists) {
        for (var list : lists) {
            if (list.isEmpty()) {
                fail("INITIAL_CONTENT_REQUIRED_DATA_MISSING");
            }
        }
    }

    private void verifyCounts(
            JsonNode counts,
            List<?> media,
            List<?> breeds,
            List<?> services,
            List<?> notices,
            List<?> galleryItems) {
        if (requiredInt(counts, "media") != media.size()
                || requiredInt(counts, "breeds") != breeds.size()
                || requiredInt(counts, "services") != services.size()
                || requiredInt(counts, "notices") != notices.size()
                || requiredInt(counts, "galleryItems") != galleryItems.size()) {
            fail("INITIAL_CONTENT_COUNT_INVALID");
        }
    }

    private void verifyReferences(
            InitialContentBundle.Shop shop,
            Set<String> mediaKeys,
            List<InitialContentBundle.Breed> breeds,
            List<InitialContentBundle.Service> services,
            List<InitialContentBundle.GalleryItem> galleryItems) {
        var breedKeys = breeds.stream().map(InitialContentBundle.Breed::key).collect(java.util.stream.Collectors.toSet());
        var serviceKeys = services.stream().map(InitialContentBundle.Service::key).collect(java.util.stream.Collectors.toSet());
        var referencedMedia = new HashSet<String>();
        add(referencedMedia, shop.heroMediaKey());
        add(referencedMedia, shop.groomerMediaKey());
        add(referencedMedia, shop.ogMediaKey());
        for (var item : galleryItems) {
            if (!breedKeys.contains(item.breedKey())
                    || !serviceKeys.contains(item.primaryServiceKey())) {
                fail("INITIAL_CONTENT_RELATION_INVALID");
            }
            add(referencedMedia, item.coverMediaKey());
            add(referencedMedia, item.beforeMediaKey());
            add(referencedMedia, item.afterMediaKey());
        }
        if (!mediaKeys.equals(referencedMedia)) {
            fail("INITIAL_CONTENT_RELATION_INVALID");
        }
    }

    private void add(Set<String> values, String value) {
        if (value != null) {
            values.add(value);
        }
    }

    private Path requireBundleRoot(Path inputRoot) throws IOException {
        if (inputRoot == null) {
            fail("INITIAL_CONTENT_PATH_INVALID");
        }
        var root = inputRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            fail("INITIAL_CONTENT_PATH_INVALID");
        }
        return root;
    }

    private Set<String> inventory(Path root) throws IOException {
        var files = new LinkedHashSet<String>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    fail("INITIAL_CONTENT_LINK_INVALID");
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    var relative = portableRelative(root, path);
                    if (!relative.isEmpty() && !"media".equals(relative)) {
                        fail("INITIAL_CONTENT_FILE_INVENTORY_INVALID");
                    }
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    fail("INITIAL_CONTENT_FILE_INVENTORY_INVALID");
                }
                var linkCount = ((Number) Files.getAttribute(
                                path, "unix:nlink", LinkOption.NOFOLLOW_LINKS))
                        .longValue();
                if (linkCount != 1) {
                    fail("INITIAL_CONTENT_LINK_INVALID");
                }
                var relative = portableRelative(root, path);
                if (!files.add(relative)) {
                    fail("INITIAL_CONTENT_FILE_INVENTORY_INVALID");
                }
            }
        }
        return Set.copyOf(files);
    }

    private String portableRelative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private Path resolveDirectFile(Path root, String relativePath) {
        var relative = Path.of(relativePath);
        if (relative.isAbsolute()
                || relative.getNameCount() != 2
                || !"media".equals(relative.getName(0).toString())
                || !relative.normalize().equals(relative)) {
            fail("INITIAL_CONTENT_PATH_INVALID");
        }
        var resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root.resolve("media"))
                || !resolved.getParent().equals(root.resolve("media"))) {
            fail("INITIAL_CONTENT_PATH_INVALID");
        }
        return resolved;
    }

    private JsonNode parse(Path path) throws IOException {
        try (var input = Files.newInputStream(path); var parser = objectMapper.createParser(input)) {
            var node = objectMapper.readTree(parser);
            if (node == null || parser.nextToken() != null) {
                fail("INITIAL_CONTENT_JSON_INVALID");
            }
            return node;
        }
    }

    private void verifyFile(Path path, long expectedSize, String expectedSha) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) != expectedSize
                || !sha256(path).equals(expectedSha)) {
            fail("INITIAL_CONTENT_CHECKSUM_INVALID");
        }
    }

    private String sha256(Path path) throws IOException {
        var digest = sha256Digest();
        try (var input = Files.newInputStream(path)) {
            var buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private JsonNode object(JsonNode parent, String field, Set<String> fields) {
        var node = parent.get(field);
        requireFields(node, fields);
        return node;
    }

    private JsonNode array(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || !node.isArray()) {
            fail("INITIAL_CONTENT_JSON_INVALID");
        }
        return node;
    }

    private void requireFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || !new HashSet<>(node.propertyNames()).equals(fields)) {
            fail("INITIAL_CONTENT_JSON_INVALID");
        }
    }

    private void requireSchemaVersion(JsonNode node) {
        if (requiredInt(node, "schemaVersion") != SCHEMA_VERSION) {
            fail("INITIAL_CONTENT_SCHEMA_UNSUPPORTED");
        }
    }

    private String uniqueKey(JsonNode node, Set<String> keys) {
        var key = logicalKey(node, "key");
        if (!keys.add(key)) {
            fail("INITIAL_CONTENT_DUPLICATE_KEY");
        }
        return key;
    }

    private String logicalKey(JsonNode node, String field) {
        return matchingText(node, field, KEY);
    }

    private String optionalKey(JsonNode node, String field) {
        var value = optionalText(node, field);
        if (value != null && !KEY.matcher(value).matches()) {
            fail("INITIAL_CONTENT_KEY_INVALID");
        }
        return value;
    }

    private String sha256Text(JsonNode node, String field) {
        return matchingText(node, field, SHA256);
    }

    private String matchingText(JsonNode node, String field, Pattern pattern) {
        var value = requiredText(node, field);
        if (!pattern.matcher(value).matches()) {
            fail("INITIAL_CONTENT_JSON_INVALID");
        }
        return value;
    }

    private String requiredText(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isString()) {
            fail("INITIAL_CONTENT_JSON_INVALID");
        }
        return value.asText();
    }

    private String optionalText(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null) {
            fail("INITIAL_CONTENT_JSON_INVALID");
        }
        if (value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            fail("INITIAL_CONTENT_JSON_INVALID");
        }
        return value.asText();
    }

    private int requiredInt(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            fail("INITIAL_CONTENT_JSON_INVALID");
        }
        return value.asInt();
    }

    private long positiveLong(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            fail("INITIAL_CONTENT_JSON_INVALID");
        }
        var parsed = value.asLong();
        if (parsed <= 0) {
            fail("INITIAL_CONTENT_JSON_INVALID");
        }
        return parsed;
    }

    private boolean requiredBoolean(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isBoolean()) {
            fail("INITIAL_CONTENT_JSON_INVALID");
        }
        return value.asBoolean();
    }

    private Instant requiredInstant(JsonNode node, String field) {
        var value = optionalInstant(node, field);
        if (value == null) {
            fail("INITIAL_CONTENT_JSON_INVALID");
        }
        return value;
    }

    private Instant optionalInstant(JsonNode node, String field) {
        var value = optionalText(node, field);
        if (value == null) {
            return null;
        }
        if (!UTC_MICROSECOND_INSTANT.matcher(value).matches()) {
            fail("INITIAL_CONTENT_JSON_INVALID");
        }
        try {
            var parsed = Instant.parse(value);
            if (parsed.getNano() % 1_000 != 0) {
                fail("INITIAL_CONTENT_JSON_INVALID");
            }
            return parsed;
        } catch (RuntimeException exception) {
            fail("INITIAL_CONTENT_JSON_INVALID");
            return null;
        }
    }

    private void requireInventoryContains(Set<String> inventory, String relativePath) {
        if (!inventory.contains(relativePath)) {
            fail("INITIAL_CONTENT_FILE_INVENTORY_INVALID");
        }
    }

    private void fail(String code) {
        throw new InitialContentImportException(code);
    }
}
