package kr.co.rhaomi.backend.notice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.rhaomi.backend.content.ContentStatus;
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

    @Query("""
            SELECT notice
            FROM Notice notice
            WHERE notice.status = :status
              AND notice.publishedAt <= :generatedAt
              AND (notice.expiresAt IS NULL OR notice.expiresAt > :generatedAt)
            ORDER BY notice.pinned DESC,
                     notice.publishedAt DESC,
                     notice.audit.updatedAt DESC,
                     notice.id ASC
            """)
    List<Notice> findAllForBuild(ContentStatus status, Instant generatedAt);
}
