package kr.co.rhaomi.backend.notice;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {

    boolean existsBySlug(String slug);

    @Query("""
            SELECT notice
            FROM Notice notice
            ORDER BY notice.pinned DESC,
                     notice.publishedAt DESC NULLS LAST,
                     notice.audit.updatedAt DESC,
                     notice.id ASC
            """)
    List<Notice> findAllForAdmin();
}
