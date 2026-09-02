package kr.co.rhaomi.backend.media;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {
    List<MediaAsset> findAllByOrderByAuditCreatedAtDescIdAsc();

    @Query("""
            SELECT media
            FROM MediaAsset media
            WHERE media.id IN :ids
            ORDER BY media.id ASC
            """)
    List<MediaAsset> findAllForBuild(Set<UUID> ids);
}
