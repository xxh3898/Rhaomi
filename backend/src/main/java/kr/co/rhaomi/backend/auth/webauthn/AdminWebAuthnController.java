package kr.co.rhaomi.backend.auth.webauthn;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth/webauthn")
public class AdminWebAuthnController {

    private final AdminWebAuthnService service;

    AdminWebAuthnController(AdminWebAuthnService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public AdminWebAuthnStatusResponse status(Authentication authentication) {
        return service.status(authentication);
    }

    @GetMapping("/registration/options")
    public WebAuthnRegistrationOptionsResponse registrationOptions(
            Authentication authentication, HttpServletRequest request) {
        return service.registrationOptions(authentication, request.getSession());
    }

    @PostMapping("/registration")
    public AdminWebAuthnStatusResponse register(
            Authentication authentication,
            @RequestBody WebAuthnRegistrationRequest registration,
            HttpServletRequest request,
            HttpServletResponse response) {
        return service.completeRegistration(
                authentication, registration, request, response);
    }

    @GetMapping("/authentication/options")
    public WebAuthnAuthenticationOptionsResponse authenticationOptions(
            Authentication authentication, HttpServletRequest request) {
        return service.authenticationOptions(authentication, request.getSession());
    }

    @PostMapping("/authentication")
    public AdminWebAuthnStatusResponse authenticate(
            Authentication authentication,
            @RequestBody WebAuthnAuthenticationRequest assertion,
            HttpServletRequest request,
            HttpServletResponse response) {
        return service.completeAuthentication(authentication, assertion, request, response);
    }

    @GetMapping("/credentials")
    public List<AdminWebAuthnCredentialResponse> credentials(Authentication authentication) {
        return service.credentials(authentication);
    }

    @DeleteMapping("/credentials/{credentialId}")
    public ResponseEntity<Void> revokeCredential(
            Authentication authentication, @PathVariable UUID credentialId) {
        service.revokeCredential(authentication, credentialId);
        return ResponseEntity.noContent().build();
    }
}
