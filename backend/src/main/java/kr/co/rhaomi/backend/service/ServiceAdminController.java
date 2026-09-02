package kr.co.rhaomi.backend.service;

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
@RequestMapping("/api/admin/services")
public class ServiceAdminController {

    private final ServiceAdminService serviceAdminService;

    public ServiceAdminController(ServiceAdminService serviceAdminService) {
        this.serviceAdminService = serviceAdminService;
    }

    @GetMapping
    public List<ServiceResponse> list() {
        return serviceAdminService.list();
    }

    @GetMapping("/{id}")
    public ServiceResponse get(@PathVariable UUID id) {
        return serviceAdminService.get(id);
    }

    @PostMapping
    public ResponseEntity<ServiceResponse> create(
            @Valid @RequestBody ServiceCreateRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        var response = serviceAdminService.create(request, principal.id());
        return ResponseEntity.created(URI.create("/api/admin/services/" + response.id())).body(response);
    }

    @PutMapping("/{id}")
    public ServiceResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody ServiceUpdateRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return serviceAdminService.update(id, request, principal.id());
    }
}
