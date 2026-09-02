package kr.co.rhaomi.backend.breed;

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
@RequestMapping("/api/admin/breeds")
public class BreedAdminController {

    private final BreedAdminService breedAdminService;

    public BreedAdminController(BreedAdminService breedAdminService) {
        this.breedAdminService = breedAdminService;
    }

    @GetMapping
    public List<BreedResponse> list() {
        return breedAdminService.list();
    }

    @GetMapping("/{id}")
    public BreedResponse get(@PathVariable UUID id) {
        return breedAdminService.get(id);
    }

    @PostMapping
    public ResponseEntity<BreedResponse> create(
            @Valid @RequestBody BreedCreateRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        var response = breedAdminService.create(request, principal.id());
        return ResponseEntity.created(URI.create("/api/admin/breeds/" + response.id())).body(response);
    }

    @PutMapping("/{id}")
    public BreedResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody BreedUpdateRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return breedAdminService.update(id, request, principal.id());
    }
}
