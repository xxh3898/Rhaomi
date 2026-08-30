package kr.co.rhaomi.backend.breed;

import java.util.List;
import java.util.UUID;
import kr.co.rhaomi.backend.content.ContentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BreedRepository extends JpaRepository<Breed, UUID> {

    boolean existsBySlug(String slug);

    List<Breed> findAllByOrderBySortOrderAscNameAscIdAsc();

    List<Breed> findAllByStatusOrderBySortOrderAscNameAscIdAsc(ContentStatus status);
}
