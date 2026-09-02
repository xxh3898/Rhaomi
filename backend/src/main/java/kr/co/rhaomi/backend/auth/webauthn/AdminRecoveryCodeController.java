package kr.co.rhaomi.backend.auth.webauthn;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth/recovery-codes")
public class AdminRecoveryCodeController {

    private final AdminRecoveryCodeService service;

    AdminRecoveryCodeController(AdminRecoveryCodeService service) {
        this.service = service;
    }

    @PostMapping("/verify")
    public AdminWebAuthnStatusResponse verify(
            Authentication authentication,
            @RequestBody RecoveryCodeRequest recoveryCode,
            HttpServletRequest request,
            HttpServletResponse response) {
        return service.verify(authentication, recoveryCode, request, response);
    }

    @PostMapping("/rotate")
    public RecoveryCodesResponse rotate(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        return service.rotate(authentication, request, response);
    }
}
