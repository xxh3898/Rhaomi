package kr.co.rhaomi.backend.auth.webauthn;

import java.util.Set;
import kr.co.rhaomi.backend.config.AdminWebAuthnProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;

@Configuration
class AdminWebAuthnConfiguration {

    @Bean
    WebAuthnRelyingPartyOperations adminWebAuthnRelyingPartyOperations(
            PublicKeyCredentialUserEntityRepository users,
            UserCredentialRepository credentials,
            AdminWebAuthnProperties properties) {
        var rp = PublicKeyCredentialRpEntity.builder()
                .id(properties.rpId())
                .name(properties.rpName())
                .build();
        var operations = new Webauthn4JRelyingPartyOperations(
                users, credentials, rp, Set.of(properties.origin()));
        operations.setCustomizeCreationOptions(builder -> builder
                .challenge(Bytes.random())
                .timeout(properties.challengeTtl())
                .attestation(AttestationConveyancePreference.NONE)
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build()));
        operations.setCustomizeRequestOptions(builder -> builder
                .challenge(Bytes.random())
                .timeout(properties.challengeTtl())
                .userVerification(UserVerificationRequirement.REQUIRED));
        return operations;
    }
}
