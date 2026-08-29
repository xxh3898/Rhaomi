package kr.co.rhaomi.backend.media;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class MediaFormatDetector {

    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final int MAX_BOX_HEADER_BYTES = 64 * 1024;
    private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "hevc", "hevx");
    private static final Set<String> HEIF_SEQUENCE_BRANDS = Set.of("msf1", "hevs", "hevm");
    private static final Set<String> AVIF_BRANDS = Set.of("avif", "avis");

    MediaSourceType detect(Path path) {
        byte[] header;
        try (var input = Files.newInputStream(path)) {
            header = input.readNBytes(MAX_BOX_HEADER_BYTES);
        } catch (IOException exception) {
            throw new MediaStorageException();
        }

        if (isJpeg(header)) {
            return MediaSourceType.JPEG;
        }
        if (startsWith(header, PNG_SIGNATURE)) {
            if (isApng(path)) {
                throw new MediaInvalidImageException();
            }
            return MediaSourceType.PNG;
        }
        return detectHeif(header);
    }

    void validateDeclaredMetadata(MediaSourceType type, String declaredContentType, String filename) {
        var normalizedContentType = declaredContentType == null
                ? ""
                : declaredContentType.trim().toLowerCase(Locale.ROOT);
        if (!normalizedContentType.isEmpty()
                && !normalizedContentType.equals("application/octet-stream")
                && !normalizedContentType.equals(type.sourceContentType())) {
            throw new MediaTypeUnsupportedException();
        }

        var extension = filenameExtension(filename);
        if (extension == null) {
            return;
        }
        var matches = switch (type) {
            case JPEG -> extension.equals("jpg") || extension.equals("jpeg");
            case PNG -> extension.equals("png");
            case HEIC -> extension.equals("heic");
            case HEIF -> extension.equals("heif") || extension.equals("hif");
        };
        if (!matches) {
            throw new MediaTypeUnsupportedException();
        }
    }

    private MediaSourceType detectHeif(byte[] header) {
        var buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        int offset = 0;
        while (offset + 8 <= header.length) {
            long size = Integer.toUnsignedLong(buffer.getInt(offset));
            var type = ascii(header, offset + 4);
            int headerSize = 8;
            if (size == 1) {
                if (offset + 16 > header.length) {
                    break;
                }
                size = buffer.getLong(offset + 8);
                headerSize = 16;
            } else if (size == 0) {
                size = header.length - offset;
            }
            if (size < headerSize || size > Integer.MAX_VALUE || offset + size > header.length) {
                break;
            }
            if (type.equals("ftyp")) {
                return detectHeifBrands(header, offset + headerSize, (int) size - headerSize);
            }
            offset += (int) size;
        }
        throw new MediaTypeUnsupportedException();
    }

    private MediaSourceType detectHeifBrands(byte[] bytes, int offset, int length) {
        if (length < 8) {
            throw new MediaInvalidImageException();
        }
        var majorBrand = ascii(bytes, offset);
        var brands = new HashSet<String>();
        brands.add(majorBrand);
        for (int brandOffset = offset + 8; brandOffset + 4 <= offset + length; brandOffset += 4) {
            brands.add(ascii(bytes, brandOffset));
        }

        if (brands.stream().anyMatch(AVIF_BRANDS::contains)) {
            throw new MediaTypeUnsupportedException();
        }
        if (brands.stream().anyMatch(HEIF_SEQUENCE_BRANDS::contains)) {
            throw new MediaInvalidImageException();
        }
        if (HEIC_BRANDS.contains(majorBrand)) {
            return MediaSourceType.HEIC;
        }
        if (majorBrand.equals("mif1")) {
            return MediaSourceType.HEIF;
        }
        throw new MediaTypeUnsupportedException();
    }

    private boolean isApng(Path path) {
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            var signature = input.readNBytes(PNG_SIGNATURE.length);
            if (!startsWith(signature, PNG_SIGNATURE)) {
                throw new MediaInvalidImageException();
            }
            while (true) {
                long length = Integer.toUnsignedLong(input.readInt());
                var chunkType = new String(input.readNBytes(4), StandardCharsets.US_ASCII);
                if (chunkType.equals("acTL")) {
                    return true;
                }
                if (chunkType.equals("IDAT") || chunkType.equals("IEND")) {
                    return false;
                }
                if (length > Integer.MAX_VALUE) {
                    throw new MediaInvalidImageException();
                }
                input.skipNBytes(length + 4);
            }
        } catch (EOFException exception) {
            throw new MediaInvalidImageException();
        } catch (IOException exception) {
            throw new MediaInvalidImageException();
        }
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && bytes[0] == (byte) 0xff
                && bytes[1] == (byte) 0xd8
                && bytes[2] == (byte) 0xff;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            throw new MediaInvalidImageException();
        }
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    private static String filenameExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        var lastSeparator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        var dot = filename.lastIndexOf('.');
        if (dot <= lastSeparator || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
