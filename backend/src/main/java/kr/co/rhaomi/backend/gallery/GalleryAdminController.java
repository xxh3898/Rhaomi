package kr.co.rhaomi.backend.gallery;

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
@RequestMapping("/api/admin/gallery-items")
public class GalleryAdminController {

    private final GalleryAdminService galleryAdminService;

    public GalleryAdminController(GalleryAdminService galleryAdminService) {
        this.galleryAdminService = galleryAdminService;
    }

    @GetMapping
    public List<GalleryResponse> list() {
        return galleryAdminService.list();
    }

    @GetMapping("/{id}")
    public GalleryResponse get(@PathVariable UUID id) {
        return galleryAdminService.get(id);
    }

    @PostMapping
    public ResponseEntity<GalleryResponse> create(
            @Valid @RequestBody GalleryCreateRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        var response = galleryAdminService.create(request, principal.id());
        return ResponseEntity.created(URI.create("/api/admin/gallery-items/" + response.id()))
                .body(response);
    }

    @PutMapping("/{id}")
    public GalleryResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody GalleryUpdateRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return galleryAdminService.update(id, request, principal.id());
    }
}
