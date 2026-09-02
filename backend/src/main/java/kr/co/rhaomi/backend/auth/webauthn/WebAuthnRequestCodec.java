package kr.co.rhaomi.backend.auth.webauthn;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.web.webauthn.api.AuthenticationExtensionsClientOutput;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttachment;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientOutputs;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;

final class WebAuthnRequestCodec {

    private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]+$");

    private WebAuthnRequestCodec() {}

    static PublicKeyCredential<AuthenticatorAttestationResponse> registration(
            WebAuthnRegistrationRequest request) {
        if (request == null || request.response() == null) {
            throw new WebAuthnInvalidRequestException();
        }
        requirePublicKey(request.type());
        var rawId = bytes(request.rawId());
        if (!request.id().equals(rawId.toBase64UrlString())) {
            throw new WebAuthnInvalidRequestException();
        }
        var response = request.response();
        List<AuthenticatorTransport> transports = response.transports() == null
                ? List.of()
                : response.transports().stream().map(WebAuthnRequestCodec::transport).toList();
        PublicKeyCredential.PublicKeyCredentialBuilder<AuthenticatorAttestationResponse> builder =
                PublicKeyCredential.builder();
        builder.id(request.id());
        builder.rawId(rawId);
        builder.type(PublicKeyCredentialType.PUBLIC_KEY);
        builder.response(AuthenticatorAttestationResponse.builder()
                .clientDataJSON(bytes(response.clientDataJSON()))
                .attestationObject(bytes(response.attestationObject()))
                .transports(transports)
                .build());
        builder.clientExtensionResults(new ImmutableAuthenticationExtensionsClientOutputs(
                List.<AuthenticationExtensionsClientOutput<?>>of()));
        if (request.authenticatorAttachment() != null) {
            builder.authenticatorAttachment(attachment(request.authenticatorAttachment()));
        }
        return builder.build();
    }

    static PublicKeyCredential<AuthenticatorAssertionResponse> assertion(
            WebAuthnAuthenticationRequest request) {
        if (request == null || request.response() == null) {
            throw new WebAuthnInvalidRequestException();
        }
        requirePublicKey(request.type());
        var rawId = bytes(request.rawId());
        if (!request.id().equals(rawId.toBase64UrlString())) {
            throw new WebAuthnInvalidRequestException();
        }
        var response = request.response();
        PublicKeyCredential.PublicKeyCredentialBuilder<AuthenticatorAssertionResponse> builder =
                PublicKeyCredential.builder();
        builder.id(request.id());
        builder.rawId(rawId);
        builder.type(PublicKeyCredentialType.PUBLIC_KEY);
        builder.response(AuthenticatorAssertionResponse.builder()
                .clientDataJSON(bytes(response.clientDataJSON()))
                .authenticatorData(bytes(response.authenticatorData()))
                .signature(bytes(response.signature()))
                .userHandle(nullableBytes(response.userHandle()))
                .build());
        builder.clientExtensionResults(new ImmutableAuthenticationExtensionsClientOutputs(
                List.<AuthenticationExtensionsClientOutput<?>>of()));
        if (request.authenticatorAttachment() != null) {
            builder.authenticatorAttachment(attachment(request.authenticatorAttachment()));
        }
        return builder.build();
    }

    static Bytes bytes(String value) {
        if (value == null || !BASE64URL.matcher(value).matches()) {
            throw new WebAuthnInvalidRequestException();
        }
        try {
            var decoded = Bytes.fromBase64(value);
            if (!value.equals(decoded.toBase64UrlString())) {
                throw new WebAuthnInvalidRequestException();
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new WebAuthnInvalidRequestException();
        }
    }

    private static Bytes nullableBytes(String value) {
        return value == null ? null : bytes(value);
    }

    private static void requirePublicKey(String value) {
        if (!"public-key".equals(value)) {
            throw new WebAuthnInvalidRequestException();
        }
    }

    private static AuthenticatorTransport transport(String value) {
        try {
            return AuthenticatorTransport.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new WebAuthnInvalidRequestException();
        }
    }

    private static AuthenticatorAttachment attachment(String value) {
        try {
            return AuthenticatorAttachment.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new WebAuthnInvalidRequestException();
        }
    }
}
