package kr.co.rhaomi.backend.auth.webauthn;

import java.util.List;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;

public record WebAuthnAuthenticationOptionsResponse(
        String challenge,
        long timeout,
        String rpId,
        List<WebAuthnRegistrationOptionsResponse.Descriptor> allowCredentials,
        String userVerification) {

    static WebAuthnAuthenticationOptionsResponse from(PublicKeyCredentialRequestOptions options) {
        return new WebAuthnAuthenticationOptionsResponse(
                options.getChallenge().toBase64UrlString(),
                options.getTimeout().toMillis(),
                options.getRpId(),
                options.getAllowCredentials().stream()
                        .map(descriptor -> new WebAuthnRegistrationOptionsResponse.Descriptor(
                                descriptor.getType().getValue(),
                                descriptor.getId().toBase64UrlString(),
                                descriptor.getTransports().stream()
                                        .map(transport -> transport.getValue())
                                        .toList()))
                        .toList(),
                options.getUserVerification().getValue());
    }
}
