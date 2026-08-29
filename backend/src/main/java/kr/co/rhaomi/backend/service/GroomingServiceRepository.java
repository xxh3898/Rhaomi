package kr.co.rhaomi.backend.service;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroomingServiceRepository extends JpaRepository<GroomingService, UUID> {

    boolean existsBySlug(String slug);

    List<GroomingService> findAllByOrderBySortOrderAscNameAscIdAsc();
}
