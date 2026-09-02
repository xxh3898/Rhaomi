package kr.co.rhaomi.backend.auth.webauthn;

public record WebAuthnAuthenticationRequest(
        String id,
        String rawId,
        String type,
        String authenticatorAttachment,
        WebAuthnRegistrationRequest.ClientExtensionResults clientExtensionResults,
        AssertionResponse response) {

    public record AssertionResponse(
            String clientDataJSON, String authenticatorData, String signature, String userHandle) {}
}
