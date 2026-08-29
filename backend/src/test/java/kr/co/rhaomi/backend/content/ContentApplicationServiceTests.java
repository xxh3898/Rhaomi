package kr.co.rhaomi.backend.content;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import kr.co.rhaomi.backend.breed.BreedAdminService;
import kr.co.rhaomi.backend.breed.BreedCreateRequest;
import kr.co.rhaomi.backend.breed.BreedRepository;
import kr.co.rhaomi.backend.notice.NoticeAdminService;
import kr.co.rhaomi.backend.notice.NoticeCreateRequest;
import kr.co.rhaomi.backend.notice.NoticeRepository;
import kr.co.rhaomi.backend.service.GroomingServiceRepository;
import kr.co.rhaomi.backend.service.ServiceAdminService;
import kr.co.rhaomi.backend.service.ServiceUpdateRequest;
import org.junit.jupiter.api.Test;

class ContentApplicationServiceTests {

    @Test
    void should_rejectBeforeRepositoryAccess_when_createActorIsMissing() {
        var repository = mock(BreedRepository.class);
        var service = new BreedAdminService(repository);
        var request = new BreedCreateRequest("푸들", "poodle", null, 100);

        assertThrows(NullPointerException.class, () -> service.create(request, null));

        verifyNoInteractions(repository);
    }

    @Test
    void should_rejectBeforeRepositoryAccess_when_updateActorIsMissing() {
        var repository = mock(GroomingServiceRepository.class);
        var service = new ServiceAdminService(repository);
        var request = new ServiceUpdateRequest("draft", "전체미용", null, null, 100);

        assertThrows(NullPointerException.class, () -> service.update(UUID.randomUUID(), request, null));

        verifyNoInteractions(repository);
    }

    @Test
    void should_rejectBeforeRepositoryAccess_when_noticeCreateActorIsMissing() {
        var repository = mock(NoticeRepository.class);
        var service = new NoticeAdminService(repository);
        var request = new NoticeCreateRequest(
                "휴무 안내", "holiday-notice", null, null, false, null, null);

        assertThrows(NullPointerException.class, () -> service.create(request, null));

        verifyNoInteractions(repository);
    }
}
