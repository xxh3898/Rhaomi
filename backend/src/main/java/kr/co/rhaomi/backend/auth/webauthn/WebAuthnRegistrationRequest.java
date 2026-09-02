package kr.co.rhaomi.backend.auth.webauthn;

import java.util.List;

public record WebAuthnRegistrationRequest(
        String id,
        String rawId,
        String type,
        String authenticatorAttachment,
        ClientExtensionResults clientExtensionResults,
        RegistrationResponse response,
        String label) {

    public record ClientExtensionResults(CredentialProperties credProps) {}

    public record CredentialProperties(Boolean rk) {}

    public record RegistrationResponse(
            String clientDataJSON, String attestationObject, List<String> transports) {}
}
