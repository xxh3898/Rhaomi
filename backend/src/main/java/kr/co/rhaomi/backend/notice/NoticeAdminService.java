package kr.co.rhaomi.backend.notice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.co.rhaomi.backend.content.ContentNotFoundException;
import kr.co.rhaomi.backend.content.ContentPersistenceErrors;
import kr.co.rhaomi.backend.content.ContentStatus;
import kr.co.rhaomi.backend.content.SlugConflictException;
import kr.co.rhaomi.backend.publication.PublicationEventKind;
import kr.co.rhaomi.backend.publication.PublicationRecorder;
import kr.co.rhaomi.backend.publication.PublicationSourceType;
import kr.co.rhaomi.backend.publication.ScheduledPublicationEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoticeAdminService {

    private static final String SLUG_CONSTRAINT = "uk_notices_slug";

    private final NoticeRepository noticeRepository;
    private final PublicationRecorder publicationRecorder;

    public NoticeAdminService(
            NoticeRepository noticeRepository, PublicationRecorder publicationRecorder) {
        this.noticeRepository = noticeRepository;
        this.publicationRecorder = publicationRecorder;
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
        var saved = save(notice);
        publicationRecorder.record(
                PublicationSourceType.NOTICE,
                saved.getId(),
                false,
                changedBoundaries(null, null, saved));
        return NoticeResponse.from(saved);
    }

    @Transactional
    public NoticeResponse update(UUID id, NoticeUpdateRequest request, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        var notice = find(id);
        var beforeStatus = notice.getStatus();
        var beforePublishedAt = notice.getPublishedAt();
        var beforeExpiresAt = notice.getExpiresAt();
        notice.update(
                ContentStatus.fromApiValue(request.status()),
                request.title(),
                request.summary(),
                request.bodyMarkdown(),
                request.pinned(),
                request.publishedAt(),
                request.expiresAt(),
                actorId);
        var saved = noticeRepository.saveAndFlush(notice);
        var contentChanged = beforeStatus == ContentStatus.PUBLISHED
                || saved.getStatus() == ContentStatus.PUBLISHED;
        publicationRecorder.record(
                PublicationSourceType.NOTICE,
                saved.getId(),
                contentChanged,
                changedBoundaries(beforePublishedAt, beforeExpiresAt, saved));
        return NoticeResponse.from(saved);
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

    private ScheduledPublicationEvent[] changedBoundaries(
            Instant beforePublishedAt, Instant beforeExpiresAt, Notice notice) {
        var events = new ArrayList<ScheduledPublicationEvent>(2);
        if (notice.getPublishedAt() != null
                && !Objects.equals(beforePublishedAt, notice.getPublishedAt())) {
            events.add(new ScheduledPublicationEvent(
                    PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                    notice.getPublishedAt()));
        }
        if (notice.getExpiresAt() != null
                && !Objects.equals(beforeExpiresAt, notice.getExpiresAt())) {
            events.add(new ScheduledPublicationEvent(
                    PublicationEventKind.NOTICE_EXPIRES_AT_DUE,
                    notice.getExpiresAt()));
        }
        return events.toArray(ScheduledPublicationEvent[]::new);
    }
}
