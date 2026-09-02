package kr.co.rhaomi.backend.auth.webauthn;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.co.rhaomi.backend.auth.AdminAuthenticationStage;
import kr.co.rhaomi.backend.config.AdminWebAuthnProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class AdminWebAuthnService {

    private static final String REGISTRATION_SESSION_ATTRIBUTE =
            AdminWebAuthnService.class.getName() + ".REGISTRATION";
    private static final String AUTHENTICATION_SESSION_ATTRIBUTE =
            AdminWebAuthnService.class.getName() + ".AUTHENTICATION";

    private final WebAuthnRelyingPartyOperations operations;
    private final AdminWebAuthnCredentialRepository credentials;
    private final AdminRecoveryCodeRepository recoveryCodes;
    private final AdminWebAuthnProperties properties;
    private final AdminAuthenticationStageService authenticationStages;
    private final AdminAccountSecurityService accountSecurity;
    private final Clock clock;
    private final TransactionTemplate transactions;

    AdminWebAuthnService(
            WebAuthnRelyingPartyOperations operations,
            AdminWebAuthnCredentialRepository credentials,
            AdminRecoveryCodeRepository recoveryCodes,
            AdminWebAuthnProperties properties,
            AdminAuthenticationStageService authenticationStages,
            AdminAccountSecurityService accountSecurity,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.operations = operations;
        this.credentials = credentials;
        this.recoveryCodes = recoveryCodes;
        this.properties = properties;
        this.authenticationStages = authenticationStages;
        this.accountSecurity = accountSecurity;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    AdminWebAuthnStatusResponse status(Authentication authentication) {
        var principal = AdminAuthenticationStageService.principal(authentication);
        accountSecurity.requireActive(principal.id());
        return status(principal.id(), principal.authenticationStage());
    }

    @Transactional(readOnly = true)
    WebAuthnRegistrationOptionsResponse registrationOptions(
            Authentication authentication, HttpSession session) {
        var principal = AdminAuthenticationStageService.principal(authentication);
        accountSecurity.requireActive(principal.id());
        requireEnrollmentAllowed(principal.id(), principal.authenticationStage());
        var options = operations.createPublicKeyCredentialCreationOptions(
                new ImmutablePublicKeyCredentialCreationOptionsRequest(authentication));
        requireChallenge(options.getChallenge().getBytes());
        session.setAttribute(
                REGISTRATION_SESSION_ATTRIBUTE,
                new RegistrationCeremony(principal.id(), clock.instant(), options));
        return WebAuthnRegistrationOptionsResponse.from(options);
    }

    AdminWebAuthnStatusResponse completeRegistration(
            Authentication authentication,
            WebAuthnRegistrationRequest registration,
            HttpServletRequest request,
            HttpServletResponse response) {
        var principal = AdminAuthenticationStageService.principal(authentication);
        var ceremony = consumeRegistration(request.getSession(false));
        if (registration == null) {
            throw new WebAuthnVerificationException();
        }
        var label = normalizeLabel(registration.label());
        var completedStatus = Objects.requireNonNull(transactions.execute(ignored -> {
            accountSecurity.requireActiveForUpdate(principal.id());
            requireCeremony(ceremony.adminId(), ceremony.issuedAt(), principal.id());
            requireEnrollmentAllowed(principal.id(), principal.authenticationStage());
            try {
                operations.registerCredential(new ImmutableRelyingPartyRegistrationRequest(
                        ceremony.options(),
                        new RelyingPartyPublicKey(
                                WebAuthnRequestCodec.registration(registration), label)));
                credentials.flush();
            } catch (DataIntegrityViolationException exception) {
                throw new WebAuthnVerificationException();
            } catch (DataAccessException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new WebAuthnVerificationException();
            }
            return status(principal.id(), AdminAuthenticationStage.SECOND_FACTOR_VERIFIED);
        }));
        authenticationStages.promote(
                authentication,
                AdminAuthenticationStage.SECOND_FACTOR_VERIFIED,
                request,
                response);
        return completedStatus;
    }

    @Transactional(readOnly = true)
    WebAuthnAuthenticationOptionsResponse authenticationOptions(
            Authentication authentication, HttpSession session) {
        var principal = AdminAuthenticationStageService.principal(authentication);
        requireFirstFactor(principal.authenticationStage());
        accountSecurity.requireActive(principal.id());
        if (activeCredentialCount(principal.id()) == 0) {
            throw new WebAuthnPolicyException();
        }
        var options = operations.createCredentialRequestOptions(
                new ImmutablePublicKeyCredentialRequestOptionsRequest(authentication));
        requireChallenge(options.getChallenge().getBytes());
        session.setAttribute(
                AUTHENTICATION_SESSION_ATTRIBUTE,
                new AuthenticationCeremony(principal.id(), clock.instant(), options));
        return WebAuthnAuthenticationOptionsResponse.from(options);
    }

    AdminWebAuthnStatusResponse completeAuthentication(
            Authentication authentication,
            WebAuthnAuthenticationRequest assertion,
            HttpServletRequest request,
            HttpServletResponse response) {
        var principal = AdminAuthenticationStageService.principal(authentication);
        requireFirstFactor(principal.authenticationStage());
        var ceremony = consumeAuthentication(request.getSession(false));
        var completedStatus = Objects.requireNonNull(transactions.execute(ignored -> {
            accountSecurity.requireActiveForUpdate(principal.id());
            requireCeremony(ceremony.adminId(), ceremony.issuedAt(), principal.id());
            try {
                var user = operations.authenticate(new RelyingPartyAuthenticationRequest(
                        ceremony.options(), WebAuthnRequestCodec.assertion(assertion)));
                if (!user.getId().equals(
                        AdminWebAuthnUserEntityRepository.userHandle(principal.id()))) {
                    throw new WebAuthnVerificationException();
                }
                credentials.flush();
            } catch (DataAccessException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new WebAuthnVerificationException();
            }
            return status(principal.id(), AdminAuthenticationStage.SECOND_FACTOR_VERIFIED);
        }));
        authenticationStages.promote(
                authentication,
                AdminAuthenticationStage.SECOND_FACTOR_VERIFIED,
                request,
                response);
        return completedStatus;
    }

    @Transactional(readOnly = true)
    List<AdminWebAuthnCredentialResponse> credentials(Authentication authentication) {
        var principal = requireSecondFactor(authentication);
        accountSecurity.requireActive(principal.id());
        return credentials
                .findAllByAdminUserIdAndStatusOrderByCreatedAtAscIdAsc(
                        principal.id(), AdminWebAuthnCredentialStatus.ACTIVE)
                .stream()
                .map(AdminWebAuthnCredentialResponse::from)
                .toList();
    }

    @Transactional
    void revokeCredential(Authentication authentication, UUID credentialId) {
        var principal = requireSecondFactor(authentication);
        accountSecurity.requireActiveForUpdate(principal.id());
        if (activeCredentialCount(principal.id()) <= 1
                && !recoveryCodes.existsByAdminUserIdAndUsedAtIsNullAndRevokedAtIsNull(principal.id())) {
            throw new WebAuthnPolicyException();
        }
        var credential = credentials
                .findByIdAndAdminUserIdAndStatus(
                        credentialId, principal.id(), AdminWebAuthnCredentialStatus.ACTIVE)
                .orElseThrow(WebAuthnPolicyException::new);
        credential.revoke(clock.instant());
    }

    private kr.co.rhaomi.backend.auth.AdminPrincipal requireSecondFactor(Authentication authentication) {
        var principal = AdminAuthenticationStageService.principal(authentication);
        if (principal.authenticationStage() != AdminAuthenticationStage.SECOND_FACTOR_VERIFIED) {
            throw new WebAuthnPolicyException();
        }
        return principal;
    }

    private void requireEnrollmentAllowed(UUID adminId, AdminAuthenticationStage stage) {
        if (stage == AdminAuthenticationStage.RECOVERY_ROTATION_REQUIRED
                || (activeCredentialCount(adminId) > 0
                        && stage != AdminAuthenticationStage.SECOND_FACTOR_VERIFIED)) {
            throw new WebAuthnPolicyException();
        }
    }

    private static void requireFirstFactor(AdminAuthenticationStage stage) {
        if (stage != AdminAuthenticationStage.FIRST_FACTOR_VERIFIED) {
            throw new WebAuthnPolicyException();
        }
    }

    private AdminWebAuthnStatusResponse status(
            UUID adminId, AdminAuthenticationStage authenticationStage) {
        var activeCount = activeCredentialCount(adminId);
        return new AdminWebAuthnStatusResponse(
                properties.required(),
                authenticationStage,
                activeCount,
                properties.required() && activeCount == 0,
                recoveryCodes.existsByAdminUserIdAndUsedAtIsNullAndRevokedAtIsNull(adminId));
    }

    private long activeCredentialCount(UUID adminId) {
        return credentials.countByAdminUserIdAndStatus(
                adminId, AdminWebAuthnCredentialStatus.ACTIVE);
    }

    private void requireCeremony(UUID ceremonyAdminId, Instant issuedAt, UUID adminId) {
        var now = clock.instant();
        if (!ceremonyAdminId.equals(adminId)
                || issuedAt.isAfter(now)
                || !issuedAt.plus(properties.challengeTtl()).isAfter(now)) {
            throw new WebAuthnVerificationException();
        }
    }

    private static void requireChallenge(byte[] challenge) {
        if (challenge.length < 32) {
            throw new IllegalStateException("WebAuthn challenge must contain at least 32 bytes");
        }
    }

    private static String normalizeLabel(String value) {
        if (value == null) {
            throw new WebAuthnInvalidRequestException();
        }
        var label = value.strip();
        if (label.isBlank() || label.codePointCount(0, label.length()) > 100) {
            throw new WebAuthnInvalidRequestException();
        }
        return label;
    }

    private static RegistrationCeremony consumeRegistration(HttpSession session) {
        if (session == null) {
            throw new WebAuthnVerificationException();
        }
        var stored = session.getAttribute(REGISTRATION_SESSION_ATTRIBUTE);
        session.removeAttribute(REGISTRATION_SESSION_ATTRIBUTE);
        if (!(stored instanceof RegistrationCeremony ceremony)) {
            throw new WebAuthnVerificationException();
        }
        return ceremony;
    }

    private static AuthenticationCeremony consumeAuthentication(HttpSession session) {
        if (session == null) {
            throw new WebAuthnVerificationException();
        }
        var stored = session.getAttribute(AUTHENTICATION_SESSION_ATTRIBUTE);
        session.removeAttribute(AUTHENTICATION_SESSION_ATTRIBUTE);
        if (!(stored instanceof AuthenticationCeremony ceremony)) {
            throw new WebAuthnVerificationException();
        }
        return ceremony;
    }

    private record RegistrationCeremony(
            UUID adminId, Instant issuedAt, PublicKeyCredentialCreationOptions options)
            implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
    }

    private record AuthenticationCeremony(
            UUID adminId, Instant issuedAt, PublicKeyCredentialRequestOptions options)
            implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
    }
}
