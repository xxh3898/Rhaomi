package kr.co.rhaomi.backend.notice;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import kr.co.rhaomi.backend.auth.AdminPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/notices")
public class NoticeAdminController {

    private final NoticeAdminService noticeAdminService;

    public NoticeAdminController(NoticeAdminService noticeAdminService) {
        this.noticeAdminService = noticeAdminService;
    }

    @GetMapping
    public List<NoticeResponse> list() {
        return noticeAdminService.list();
    }

    @GetMapping("/{id}")
    public NoticeResponse get(@PathVariable UUID id) {
        return noticeAdminService.get(id);
    }

    @PostMapping
    public ResponseEntity<NoticeResponse> create(
            @Valid @RequestBody NoticeCreateRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        var response = noticeAdminService.create(request, principal.id());
        return ResponseEntity.created(URI.create("/api/admin/notices/" + response.id())).body(response);
    }

    @PutMapping("/{id}")
    public NoticeResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody NoticeUpdateRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return noticeAdminService.update(id, request, principal.id());
    }
}
