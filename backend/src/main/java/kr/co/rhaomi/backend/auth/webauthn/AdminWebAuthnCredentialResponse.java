package kr.co.rhaomi.backend.auth.webauthn;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record AdminWebAuthnCredentialResponse(
        UUID id,
        String label,
        List<String> transports,
        boolean backupEligible,
        boolean backupState,
        Instant createdAt,
        Instant lastUsedAt) {

    static AdminWebAuthnCredentialResponse from(AdminWebAuthnCredential credential) {
        return new AdminWebAuthnCredentialResponse(
                credential.id(),
                credential.label(),
                credential.transports().isEmpty()
                        ? List.of()
                        : Arrays.asList(credential.transports().split(",")),
                credential.backupEligible(),
                credential.backupState(),
                credential.createdAt(),
                credential.lastUsedAt());
    }
}
