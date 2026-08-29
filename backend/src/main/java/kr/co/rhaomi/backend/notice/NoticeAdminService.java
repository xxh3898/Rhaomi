package kr.co.rhaomi.backend.notice;

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
public class NoticeAdminService {

    private static final String SLUG_CONSTRAINT = "uk_notices_slug";

    private final NoticeRepository noticeRepository;

    public NoticeAdminService(NoticeRepository noticeRepository) {
        this.noticeRepository = noticeRepository;
    }

    @Transactional(readOnly = true)
    public List<NoticeResponse> list() {
        return noticeRepository.findAllForAdmin().stream().map(NoticeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public NoticeResponse get(UUID id) {
        return NoticeResponse.from(find(id));
    }

    @Transactional
    public NoticeResponse create(NoticeCreateRequest request, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        if (noticeRepository.existsBySlug(request.slug())) {
            throw new SlugConflictException();
        }
        var notice = Notice.create(
                request.title(),
                request.slug(),
                request.summary(),
                request.bodyMarkdown(),
                request.pinned(),
                request.publishedAt(),
                request.expiresAt(),
                actorId);
        return NoticeResponse.from(save(notice));
    }

    @Transactional
    public NoticeResponse update(UUID id, NoticeUpdateRequest request, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        var notice = find(id);
        notice.update(
                ContentStatus.fromApiValue(request.status()),
                request.title(),
                request.summary(),
                request.bodyMarkdown(),
                request.pinned(),
                request.publishedAt(),
                request.expiresAt(),
                actorId);
        return NoticeResponse.from(noticeRepository.saveAndFlush(notice));
    }

    private Notice find(UUID id) {
        return noticeRepository.findById(id).orElseThrow(ContentNotFoundException::new);
    }

    private Notice save(Notice notice) {
        try {
            return noticeRepository.saveAndFlush(notice);
        } catch (DataIntegrityViolationException exception) {
            if (ContentPersistenceErrors.isConstraint(exception, SLUG_CONSTRAINT)) {
                throw new SlugConflictException();
            }
            throw exception;
        }
    }
}
