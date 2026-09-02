package kr.co.rhaomi.backend.media;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import kr.co.rhaomi.backend.auth.AdminPrincipal;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/admin/media")
public class MediaAdminController {

    private final MediaAdminService mediaAdminService;

    public MediaAdminController(MediaAdminService mediaAdminService) {
        this.mediaAdminService = mediaAdminService;
    }

    @GetMapping
    public List<MediaResponse> list() {
        return mediaAdminService.list();
    }

    @GetMapping("/{id}")
    public MediaResponse get(@PathVariable UUID id) {
        return mediaAdminService.get(id);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable UUID id) {
        var content = mediaAdminService.content(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.byteSize())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(content.path()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaResponse> upload(
            HttpServletRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        var file = validatedFilePart(request);
        try {
            var response = mediaAdminService.upload(
                    file.getInputStream(),
                    file.getContentType(),
                    file.getSubmittedFileName(),
                    principal.id());
            return ResponseEntity.created(URI.create("/api/admin/media/" + response.id()))
                    .body(response);
        } catch (IOException exception) {
            throw new MediaStorageException();
        }
    }

    @PutMapping("/{id}")
    public MediaResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody MediaStatusUpdateRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return mediaAdminService.updateStatus(id, request.status(), principal.id());
    }

    private static Part validatedFilePart(HttpServletRequest request) {
        try {
            var parts = request.getParts();
            if (parts.size() != 1 || parts.stream().anyMatch(part -> !part.getName().equals("file"))) {
                throw new MediaInvalidRequestException();
            }
            var file = parts.iterator().next();
            if (file.getSize() == 0) {
                throw new MediaInvalidRequestException();
            }
            return file;
        } catch (MediaInvalidRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MediaInvalidRequestException();
        }
    }
}
