package kr.co.rhaomi.backend.gallery;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GalleryRepository extends JpaRepository<GalleryItem, UUID> {

    @Query("""
            SELECT item
            FROM GalleryItem item
            ORDER BY item.featured DESC,
                     item.sortOrder ASC,
                     item.publishedAt DESC NULLS LAST,
                     item.id ASC
            """)
    List<GalleryItem> findAllForAdmin();
}
