package kr.co.rhaomi.backend.media;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;

public final class MediaTestFixtures {

    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private MediaTestFixtures() {}

    public static byte[] resource(String name) {
        try (var input = MediaTestFixtures.class.getResourceAsStream("/media/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test fixture");
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Fixture read failed", exception);
        }
    }

    public static byte[] jpeg() {
        var image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, x < 2 ? Color.RED.getRGB() : Color.BLUE.getRGB());
            }
        }
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "JPEG", output)) {
                throw new IllegalStateException("JPEG writer unavailable");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("JPEG fixture generation failed", exception);
        }
    }

    public static byte[] apng() {
        var png = resource("synthetic-source.png");
        var insertionOffset = PNG_SIGNATURE.length + 25;
        var animationControl = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt(2)
                .putInt(0)
                .array();
        var chunk = pngChunk("acTL", animationControl);
        var result = new byte[png.length + chunk.length];
        System.arraycopy(png, 0, result, 0, insertionOffset);
        System.arraycopy(chunk, 0, result, insertionOffset, chunk.length);
        System.arraycopy(
                png,
                insertionOffset,
                result,
                insertionOffset + chunk.length,
                png.length - insertionOffset);
        return result;
    }

    public static byte[] oversizedPngHeader(int width, int height) {
        var header = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
                .putInt(width)
                .putInt(height)
                .put((byte) 8)
                .put((byte) 2)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .array();
        byte[] compressed;
        try (var bytes = new ByteArrayOutputStream(); var deflater = new DeflaterOutputStream(bytes)) {
            deflater.write(new byte[] {0, 0, 0, 0});
            deflater.finish();
            compressed = bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("PNG fixture generation failed", exception);
        }
        return concatenate(
                PNG_SIGNATURE,
                pngChunk("IHDR", header),
                pngChunk("IDAT", compressed),
                pngChunk("IEND", new byte[0]));
    }

    public static byte[] avifHeader() {
        return isoBmffHeader("avif", "mif1", "avif");
    }

    public static byte[] isoBmffHeader(String majorBrand, String... compatibleBrands) {
        var size = 16 + compatibleBrands.length * 4;
        var buffer = ByteBuffer.allocate(size)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(size)
                .put("ftyp".getBytes(StandardCharsets.US_ASCII))
                .put(brandBytes(majorBrand))
                .putInt(0);
        for (var compatibleBrand : compatibleBrands) {
            buffer.put(brandBytes(compatibleBrand));
        }
        return buffer.array();
    }

    public static byte[] truncatedHeic() {
        var source = resource("synthetic-orientation-metadata.heic");
        return java.util.Arrays.copyOf(source, 48);
    }

    public static byte[] truncatedHeif() {
        var source = resource("synthetic-orientation-metadata.heif");
        return java.util.Arrays.copyOf(source, 48);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static byte[] pngChunk(String type, byte[] data) {
        var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(data.length)
                .put(typeBytes)
                .put(data)
                .putInt((int) crc.getValue())
                .array();
    }

    private static byte[] concatenate(byte[]... values) {
        var length = java.util.Arrays.stream(values).mapToInt(value -> value.length).sum();
        var result = new byte[length];
        var offset = 0;
        for (var value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static byte[] brandBytes(String brand) {
        var bytes = brand.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length != 4) {
            throw new IllegalArgumentException("ISO BMFF brand must be exactly four ASCII bytes");
        }
        return bytes;
    }
}
