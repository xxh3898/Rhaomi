package kr.co.rhaomi.backend.build;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import kr.co.rhaomi.backend.media.MediaStorage;
import kr.co.rhaomi.backend.media.MediaStorageException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuildMediaService {

    private final BuildDataReader reader;
    private final MediaStorage mediaStorage;
    private final Clock clock;

    public BuildMediaService(BuildDataReader reader, MediaStorage mediaStorage, Clock clock) {
        this.reader = reader;
        this.mediaStorage = mediaStorage;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BuildMediaContent content(UUID id, long publishGeneration) {
        if (id == null || publishGeneration <= 0) {
            throw new BuildInvalidRequestException();
        }
        var generatedAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        if (!reader.isActiveGeneration(publishGeneration, generatedAt)) {
            throw new BuildGenerationNotActiveException();
        }

        var media = reader.mediaAsset(id).orElseThrow(BuildMediaNotFoundException::new);
        if (!BuildContentValidator.isValid(media)
                || !reader.isMediaInCurrentPublicScope(id, generatedAt)) {
            throw new BuildMediaNotFoundException();
        }

        try {
            var path = mediaStorage.verifiedContent(media);
            byte[] bytes;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes(Math.toIntExact(media.getByteSize() + 1));
            }
            if (bytes.length != media.getByteSize()
                    || !sha256(bytes).equals(media.getSha256())) {
                throw new BuildMediaUnavailableException();
            }
            return new BuildMediaContent(media.getContentType(), bytes);
        } catch (IOException | MediaStorageException exception) {
            throw new BuildMediaUnavailableException();
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new BuildMediaUnavailableException();
        }
    }
}
