package kr.co.rhaomi.backend.auth.webauthn;

import java.util.List;
import java.util.Map;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;

public record WebAuthnRegistrationOptionsResponse(
        Rp rp,
        User user,
        String challenge,
        List<Parameter> pubKeyCredParams,
        long timeout,
        List<Descriptor> excludeCredentials,
        AuthenticatorSelection authenticatorSelection,
        String attestation,
        Map<String, Boolean> extensions) {

    static WebAuthnRegistrationOptionsResponse from(PublicKeyCredentialCreationOptions options) {
        var selection = options.getAuthenticatorSelection();
        return new WebAuthnRegistrationOptionsResponse(
                new Rp(options.getRp().getId(), options.getRp().getName()),
                new User(
                        options.getUser().getId().toBase64UrlString(),
                        options.getUser().getName(),
                        options.getUser().getDisplayName()),
                options.getChallenge().toBase64UrlString(),
                options.getPubKeyCredParams().stream()
                        .map(parameter -> new Parameter(
                                parameter.getType().getValue(), parameter.getAlg().getValue()))
                        .toList(),
                options.getTimeout().toMillis(),
                options.getExcludeCredentials().stream()
                        .map(descriptor -> new Descriptor(
                                descriptor.getType().getValue(),
                                descriptor.getId().toBase64UrlString(),
                                descriptor.getTransports().stream()
                                        .map(transport -> transport.getValue())
                                        .toList()))
                        .toList(),
                new AuthenticatorSelection(
                        selection.getAuthenticatorAttachment() == null
                                ? null
                                : selection.getAuthenticatorAttachment().getValue(),
                        selection.getResidentKey().getValue(),
                        selection.getUserVerification().getValue()),
                options.getAttestation().getValue(),
                Map.of("credProps", true));
    }

    public record Rp(String id, String name) {}

    public record User(String id, String name, String displayName) {}

    public record Parameter(String type, long alg) {}

    public record Descriptor(String type, String id, List<String> transports) {}

    public record AuthenticatorSelection(
            String authenticatorAttachment, String residentKey, String userVerification) {}
}
