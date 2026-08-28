package kr.co.rhaomi.backend.auth;

import java.util.UUID;
import kr.co.rhaomi.backend.admin.AdminRole;

public record AdminResponse(UUID id, String email, AdminRole role) {

    public static AdminResponse from(AdminPrincipal principal) {
        return new AdminResponse(principal.id(), principal.email(), principal.role());
    }
}
