package kr.co.rhaomi.backend.auth.webauthn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;

@Entity
@Table(name = "admin_webauthn_credentials")
class AdminWebAuthnCredential {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "admin_user_id", nullable = false, updatable = false)
    private UUID adminUserId;

    @Column(name = "credential_id", nullable = false, updatable = false)
    private byte[] credentialId;

    @Column(name = "credential_type", nullable = false, updatable = false, length = 32)
    private String credentialType;

    @Column(name = "public_key_cose", nullable = false, updatable = false)
    private byte[] publicKeyCose;

    @Column(name = "signature_count", nullable = false)
    private long signatureCount;

    @Column(name = "uv_initialized", nullable = false)
    private boolean uvInitialized;

    @Column(nullable = false, length = 255)
    private String transports;

    @Column(name = "backup_eligible", nullable = false)
    private boolean backupEligible;

    @Column(name = "backup_state", nullable = false)
    private boolean backupState;

    @Column(name = "attestation_object", nullable = false, updatable = false)
    private byte[] attestationObject;

    @Column(name = "attestation_client_data_json", nullable = false, updatable = false)
    private byte[] attestationClientDataJson;

    @Column(nullable = false, length = 100)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AdminWebAuthnCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AdminWebAuthnCredential() {}

    static AdminWebAuthnCredential create(UUID adminUserId, CredentialRecord record) {
        var credential = new AdminWebAuthnCredential();
        credential.id = UUID.randomUUID();
        credential.adminUserId = adminUserId;
        credential.credentialId = record.getCredentialId().getBytes();
        credential.credentialType = record.getCredentialType().getValue();
        credential.publicKeyCose = record.getPublicKey().getBytes();
        credential.updateUsage(record);
        credential.attestationObject = requiredBytes(record.getAttestationObject());
        credential.attestationClientDataJson = requiredBytes(record.getAttestationClientDataJSON());
        credential.label = record.getLabel();
        credential.status = AdminWebAuthnCredentialStatus.ACTIVE;
        credential.createdAt = micros(record.getCreated());
        return credential;
    }

    void updateUsage(CredentialRecord record) {
        signatureCount = record.getSignatureCount();
        uvInitialized = record.isUvInitialized();
        transports = record.getTransports().stream()
                .map(AuthenticatorTransport::getValue)
                .sorted()
                .collect(Collectors.joining(","));
        backupEligible = record.isBackupEligible();
        backupState = record.isBackupState();
        lastUsedAt = micros(record.getLastUsed());
        updatedAt = lastUsedAt;
    }

    CredentialRecord toCredentialRecord() {
        Set<AuthenticatorTransport> storedTransports = transports.isEmpty()
                ? Set.of()
                : Arrays.stream(transports.split(","))
                        .map(AuthenticatorTransport::valueOf)
                        .collect(Collectors.toUnmodifiableSet());
        return ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.valueOf(credentialType))
                .credentialId(new Bytes(credentialId))
                .userEntityUserId(AdminWebAuthnUserEntityRepository.userHandle(adminUserId))
                .publicKey(new ImmutablePublicKeyCose(publicKeyCose))
                .signatureCount(signatureCount)
                .uvInitialized(uvInitialized)
                .transports(storedTransports)
                .backupEligible(backupEligible)
                .backupState(backupState)
                .attestationObject(new Bytes(attestationObject))
                .attestationClientDataJSON(new Bytes(attestationClientDataJson))
                .created(createdAt)
                .lastUsed(lastUsedAt)
                .label(label)
                .build();
    }

    void revoke(Instant now) {
        status = AdminWebAuthnCredentialStatus.REVOKED;
        revokedAt = micros(now);
        updatedAt = revokedAt;
    }

    UUID id() {
        return id;
    }

    UUID adminUserId() {
        return adminUserId;
    }

    String label() {
        return label;
    }

    String transports() {
        return transports;
    }

    boolean backupEligible() {
        return backupEligible;
    }

    boolean backupState() {
        return backupState;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant lastUsedAt() {
        return lastUsedAt;
    }

    private static byte[] requiredBytes(Bytes bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("credential attestation data is required");
        }
        return bytes.getBytes();
    }

    private static Instant micros(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
