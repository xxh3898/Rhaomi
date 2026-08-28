package kr.co.rhaomi.backend.breed;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.co.rhaomi.backend.content.ContentNotFoundException;
import kr.co.rhaomi.backend.content.ContentPersistenceErrors;
import kr.co.rhaomi.backend.content.ContentStatus;
import kr.co.rhaomi.backend.content.SlugConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BreedAdminService {

    private static final String SLUG_CONSTRAINT = "uk_breeds_slug";

    private final BreedRepository breedRepository;

    public BreedAdminService(BreedRepository breedRepository) {
        this.breedRepository = breedRepository;
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
        return BreedResponse.from(save(breed));
    }

    @Transactional
    public BreedResponse update(UUID id, BreedUpdateRequest request, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        var breed = find(id);
        breed.update(
                ContentStatus.fromApiValue(request.status()),
                request.name(),
                request.description(),
                request.sortOrder(),
                actorId);
        return BreedResponse.from(breedRepository.saveAndFlush(breed));
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
