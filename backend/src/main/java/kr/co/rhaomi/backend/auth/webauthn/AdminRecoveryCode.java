package kr.co.rhaomi.backend.auth.webauthn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "admin_recovery_codes")
class AdminRecoveryCode {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "admin_user_id", nullable = false, updatable = false)
    private UUID adminUserId;

    @Column(name = "code_set_id", nullable = false, updatable = false)
    private UUID codeSetId;

    @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AdminRecoveryCode() {}

    static AdminRecoveryCode create(
            UUID adminUserId, UUID codeSetId, String codeHash, Instant createdAt) {
        var code = new AdminRecoveryCode();
        code.id = UUID.randomUUID();
        code.adminUserId = adminUserId;
        code.codeSetId = codeSetId;
        code.codeHash = codeHash;
        code.createdAt = micros(createdAt);
        return code;
    }

    void use(Instant now) {
        usedAt = micros(now);
    }

    void revoke(Instant now) {
        revokedAt = micros(now);
    }

    UUID codeSetId() {
        return codeSetId;
    }

    String codeHash() {
        return codeHash;
    }

    private static Instant micros(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
