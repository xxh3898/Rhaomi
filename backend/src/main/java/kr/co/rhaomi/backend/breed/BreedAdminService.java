package kr.co.rhaomi.backend.breed;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.co.rhaomi.backend.content.ContentNotFoundException;
import kr.co.rhaomi.backend.content.ContentPersistenceErrors;
import kr.co.rhaomi.backend.content.ContentStatus;
import kr.co.rhaomi.backend.content.SlugConflictException;
import kr.co.rhaomi.backend.publication.PublicationRecorder;
import kr.co.rhaomi.backend.publication.PublicationSourceType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BreedAdminService {

    private static final String SLUG_CONSTRAINT = "uk_breeds_slug";

    private final BreedRepository breedRepository;
    private final PublicationRecorder publicationRecorder;

    public BreedAdminService(
            BreedRepository breedRepository, PublicationRecorder publicationRecorder) {
        this.breedRepository = breedRepository;
        this.publicationRecorder = publicationRecorder;
    }

    @Transactional(readOnly = true)
    public List<BreedResponse> list() {
        return breedRepository.findAllByOrderBySortOrderAscNameAscIdAsc().stream()
                .map(BreedResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public BreedResponse get(UUID id) {
        return BreedResponse.from(find(id));
    }

    @Transactional
    public BreedResponse create(BreedCreateRequest request, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        if (breedRepository.existsBySlug(request.slug())) {
            throw new SlugConflictException();
        }
        var breed = Breed.create(
                request.name(), request.slug(), request.description(), request.sortOrder(), actorId);
        var saved = save(breed);
        publicationRecorder.record(PublicationSourceType.BREED, saved.getId(), false);
        return BreedResponse.from(saved);
    }

    @Transactional
    public BreedResponse update(UUID id, BreedUpdateRequest request, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        var breed = find(id);
        var beforeStatus = breed.getStatus();
        breed.update(
                ContentStatus.fromApiValue(request.status()),
                request.name(),
                request.description(),
                request.sortOrder(),
                actorId);
        var saved = breedRepository.saveAndFlush(breed);
        var contentChanged = beforeStatus == ContentStatus.PUBLISHED
                || saved.getStatus() == ContentStatus.PUBLISHED;
        publicationRecorder.record(
                PublicationSourceType.BREED, saved.getId(), contentChanged);
        return BreedResponse.from(saved);
    }

    private Breed find(UUID id) {
        return breedRepository.findById(id).orElseThrow(ContentNotFoundException::new);
    }

    private Breed save(Breed breed) {
        try {
            return breedRepository.saveAndFlush(breed);
        } catch (DataIntegrityViolationException exception) {
            if (ContentPersistenceErrors.isConstraint(exception, SLUG_CONSTRAINT)) {
                throw new SlugConflictException();
            }
            throw exception;
        }
    }
}
