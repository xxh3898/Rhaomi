package kr.co.rhaomi.backend.media;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.co.rhaomi.backend.publication.PublicationRecorder;
import kr.co.rhaomi.backend.publication.PublicationSourceType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MediaAdminService {

    private final MediaAssetRepository mediaAssetRepository;
    private final MediaStorage mediaStorage;
    private final MediaImageProcessor mediaImageProcessor;
    private final PublicationRecorder publicationRecorder;

    public MediaAdminService(
            MediaAssetRepository mediaAssetRepository,
            MediaStorage mediaStorage,
            MediaImageProcessor mediaImageProcessor,
            PublicationRecorder publicationRecorder) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.mediaStorage = mediaStorage;
        this.mediaImageProcessor = mediaImageProcessor;
        this.publicationRecorder = publicationRecorder;
    }

    @Transactional(readOnly = true)
    public List<MediaResponse> list() {
        return mediaAssetRepository.findAllByOrderByAuditCreatedAtDescIdAsc().stream()
                .map(MediaResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public MediaResponse get(UUID id) {
        return MediaResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public MediaContent content(UUID id) {
        var asset = find(id);
        return new MediaContent(
                mediaStorage.verifiedContent(asset), asset.getContentType(), asset.getByteSize());
    }

    @Transactional
    public MediaResponse upload(
            InputStream inputStream,
            String declaredContentType,
            String originalFilename,
            UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        if (inputStream == null) {
            throw new MediaInvalidRequestException();
        }

        MediaSourceFile source = null;
        ProcessedMedia processed = null;
        String storageKey = null;
        var assetId = UUID.randomUUID();
        try {
            source = mediaStorage.copySource(inputStream);
            processed = mediaImageProcessor.process(source, declaredContentType, originalFilename);
            storageKey = mediaStorage.moveToMaster(
                    processed.path(), assetId, processed.fileExtension());
            registerRollbackCleanup(storageKey, assetId);

            var stored = new StoredMedia(
                    processed.sourceContentType(),
                    processed.contentType(),
                    processed.fileExtension(),
                    storageKey,
                    processed.sourceByteSize(),
                    processed.byteSize(),
                    processed.width(),
                    processed.height(),
                    processed.sha256());
            var asset = mediaAssetRepository.saveAndFlush(MediaAsset.create(assetId, stored, actorId));
            publicationRecorder.record(PublicationSourceType.MEDIA_ASSET, asset.getId(), false);
            return MediaResponse.from(asset);
        } catch (RuntimeException exception) {
            if (storageKey != null) {
                mediaStorage.deleteMasterQuietly(storageKey, assetId, "upload-rollback");
            }
            throw exception;
        } finally {
            if (processed != null) {
                mediaStorage.deleteTempQuietly(processed.path(), "processed-temp-cleanup");
            }
            if (source != null) {
                mediaStorage.deleteTempQuietly(source.path(), "source-temp-cleanup");
            }
        }
    }

    @Transactional
    public MediaResponse updateStatus(UUID id, MediaStatus status, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        var asset = find(id);
        asset.changeStatus(status, actorId);
        var saved = mediaAssetRepository.saveAndFlush(asset);
        publicationRecorder.record(PublicationSourceType.MEDIA_ASSET, saved.getId(), true);
        return MediaResponse.from(saved);
    }

    private MediaAsset find(UUID id) {
        return mediaAssetRepository.findById(id).orElseThrow(MediaNotFoundException::new);
    }

    private void registerRollbackCleanup(String storageKey, UUID assetId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            mediaStorage.deleteMasterQuietly(storageKey, assetId, "missing-transaction");
            throw new MediaStorageException();
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    mediaStorage.deleteMasterQuietly(storageKey, assetId, "transaction-rollback");
                }
            }
        });
    }
}
