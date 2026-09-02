package kr.co.rhaomi.backend.auth.webauthn;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.rhaomi.backend.auth.AdminAuthenticationStage;
import kr.co.rhaomi.backend.auth.AdminPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

@Service
class AdminAuthenticationStageService {

    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;

    AdminAuthenticationStageService(
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository) {
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
    }

    Authentication promote(
            Authentication current,
            AdminAuthenticationStage stage,
            HttpServletRequest request,
            HttpServletResponse response) {
        var principal = principal(current).withAuthenticationStage(stage);
        var promoted = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        promoted.setDetails(current.getDetails());
        sessionAuthenticationStrategy.onAuthentication(promoted, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(promoted);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return promoted;
    }

    static AdminPrincipal principal(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new WebAuthnPolicyException();
        }
        return principal;
    }
}
