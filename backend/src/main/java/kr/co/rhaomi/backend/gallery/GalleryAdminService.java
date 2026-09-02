package kr.co.rhaomi.backend.gallery;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.co.rhaomi.backend.breed.Breed;
import kr.co.rhaomi.backend.breed.BreedRepository;
import kr.co.rhaomi.backend.content.ContentStatus;
import kr.co.rhaomi.backend.media.MediaAsset;
import kr.co.rhaomi.backend.media.MediaAssetRepository;
import kr.co.rhaomi.backend.media.MediaStatus;
import kr.co.rhaomi.backend.publication.PublicationEventKind;
import kr.co.rhaomi.backend.publication.PublicationRecorder;
import kr.co.rhaomi.backend.publication.PublicationSourceType;
import kr.co.rhaomi.backend.publication.ScheduledPublicationEvent;
import kr.co.rhaomi.backend.service.GroomingService;
import kr.co.rhaomi.backend.service.GroomingServiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GalleryAdminService {

    private final GalleryRepository galleryRepository;
    private final BreedRepository breedRepository;
    private final GroomingServiceRepository serviceRepository;
    private final MediaAssetRepository mediaRepository;
    private final PublicationRecorder publicationRecorder;

    public GalleryAdminService(
            GalleryRepository galleryRepository,
            BreedRepository breedRepository,
            GroomingServiceRepository serviceRepository,
            MediaAssetRepository mediaRepository,
            PublicationRecorder publicationRecorder) {
        this.galleryRepository = galleryRepository;
        this.breedRepository = breedRepository;
        this.serviceRepository = serviceRepository;
        this.mediaRepository = mediaRepository;
        this.publicationRecorder = publicationRecorder;
    }

    @Transactional(readOnly = true)
    public List<GalleryResponse> list() {
        return galleryRepository.findAllForAdmin().stream().map(GalleryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public GalleryResponse get(UUID id) {
        return GalleryResponse.from(find(id));
    }

    @Transactional
    public GalleryResponse create(GalleryCreateRequest request, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        var values = GalleryValues.fromCreate(request);
        validateRelations(values);
        var saved = galleryRepository.saveAndFlush(GalleryItem.create(values, actorId));
        publicationRecorder.record(PublicationSourceType.GALLERY_ITEM, saved.getId(), false);
        return GalleryResponse.from(saved);
    }

    @Transactional
    public GalleryResponse update(UUID id, GalleryUpdateRequest request, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        var item = find(id);
        var beforeStatus = item.getStatus();
        var beforePublishedAt = item.getPublishedAt();
        var values = GalleryValues.fromUpdate(request);
        validateRelations(values);
        item.update(values, actorId);
        var saved = galleryRepository.saveAndFlush(item);
        var contentChanged = beforeStatus == ContentStatus.PUBLISHED
                || saved.getStatus() == ContentStatus.PUBLISHED;
        var publishBoundaryChanged = saved.getStatus() == ContentStatus.PUBLISHED
                && saved.getPublishedAt() != null
                && (beforeStatus != ContentStatus.PUBLISHED
                        || !Objects.equals(beforePublishedAt, saved.getPublishedAt()));
        if (publishBoundaryChanged) {
            publicationRecorder.record(
                    PublicationSourceType.GALLERY_ITEM,
                    saved.getId(),
                    contentChanged,
                    new ScheduledPublicationEvent(
                            PublicationEventKind.GALLERY_PUBLISHED_AT_DUE,
                            saved.getPublishedAt()));
        } else {
            publicationRecorder.record(
                    PublicationSourceType.GALLERY_ITEM, saved.getId(), contentChanged);
        }
        return GalleryResponse.from(saved);
    }

    private GalleryItem find(UUID id) {
        return galleryRepository.findById(id).orElseThrow(GalleryItemNotFoundException::new);
    }

    private void validateRelations(GalleryValues values) {
        var breed = findBreed(values.breedId());
        var service = findService(values.primaryServiceId());
        var cover = findMedia(values.coverImageId());
        var before = findMedia(values.beforeImageId());
        var after = findMedia(values.afterImageId());

        if (values.status() == ContentStatus.PUBLISHED
                && (breed == null
                        || breed.getStatus() != ContentStatus.PUBLISHED
                        || service == null
                        || service.getStatus() != ContentStatus.PUBLISHED
                        || cover == null
                        || cover.getStatus() != MediaStatus.ACTIVE
                        || (before != null && before.getStatus() != MediaStatus.ACTIVE)
                        || (after != null && after.getStatus() != MediaStatus.ACTIVE))) {
            throw new GalleryRelationInvalidException();
        }
    }

    private Breed findBreed(UUID id) {
        return id == null
                ? null
                : breedRepository.findById(id).orElseThrow(GalleryRelationInvalidException::new);
    }

    private GroomingService findService(UUID id) {
        return id == null
                ? null
                : serviceRepository.findById(id).orElseThrow(GalleryRelationInvalidException::new);
    }

    private MediaAsset findMedia(UUID id) {
        return id == null
                ? null
                : mediaRepository.findById(id).orElseThrow(GalleryRelationInvalidException::new);
    }
}
