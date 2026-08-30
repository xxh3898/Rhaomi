package kr.co.rhaomi.backend.build;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/build")
public class BuildController {

    private final BuildSnapshotService snapshotService;
    private final BuildMediaService mediaService;

    public BuildController(BuildSnapshotService snapshotService, BuildMediaService mediaService) {
        this.snapshotService = snapshotService;
        this.mediaService = mediaService;
    }

    @GetMapping("/snapshot")
    BuildSnapshotResponse snapshot(@RequestParam long publishGeneration) {
        return snapshotService.snapshot(publishGeneration);
    }

    @GetMapping("/media/{id}/content")
    ResponseEntity<byte[]> mediaContent(
            @PathVariable UUID id, @RequestParam long publishGeneration) {
        var content = mediaService.content(id, publishGeneration);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                .contentLength(content.bytes().length)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }
}
