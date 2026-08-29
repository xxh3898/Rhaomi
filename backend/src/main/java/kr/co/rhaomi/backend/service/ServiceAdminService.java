package kr.co.rhaomi.backend.service;

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
public class ServiceAdminService {

    private static final String SLUG_CONSTRAINT = "uk_services_slug";

    private final GroomingServiceRepository serviceRepository;

    public ServiceAdminService(GroomingServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> list() {
        return serviceRepository.findAllByOrderBySortOrderAscNameAscIdAsc().stream()
                .map(ServiceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ServiceResponse get(UUID id) {
        return ServiceResponse.from(find(id));
    }

    @Transactional
    public ServiceResponse create(ServiceCreateRequest request, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        if (serviceRepository.existsBySlug(request.slug())) {
            throw new SlugConflictException();
        }
        var service = GroomingService.create(
                request.name(),
                request.slug(),
                request.description(),
                request.priceText(),
                request.sortOrder(),
                actorId);
        return ServiceResponse.from(save(service));
    }

    @Transactional
    public ServiceResponse update(UUID id, ServiceUpdateRequest request, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        var service = find(id);
        service.update(
                ContentStatus.fromApiValue(request.status()),
                request.name(),
                request.description(),
                request.priceText(),
                request.sortOrder(),
                actorId);
        return ServiceResponse.from(serviceRepository.saveAndFlush(service));
    }

    private GroomingService find(UUID id) {
        return serviceRepository.findById(id).orElseThrow(ContentNotFoundException::new);
    }

    private GroomingService save(GroomingService service) {
        try {
            return serviceRepository.saveAndFlush(service);
        } catch (DataIntegrityViolationException exception) {
            if (ContentPersistenceErrors.isConstraint(exception, SLUG_CONSTRAINT)) {
                throw new SlugConflictException();
            }
            throw exception;
        }
    }
}
