package kr.co.rhaomi.backend.auth.webauthn;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class AdminWebAuthnUserCredentialRepository implements UserCredentialRepository {

    private final AdminWebAuthnCredentialRepository credentials;

    AdminWebAuthnUserCredentialRepository(AdminWebAuthnCredentialRepository credentials) {
        this.credentials = credentials;
    }

    @Override
    public void delete(Bytes credentialId) {
        throw new UnsupportedOperationException("WebAuthn credential deletion is forbidden");
    }

    @Override
    @Transactional
    public void save(CredentialRecord record) {
        var existing = credentials.findByCredentialIdAndStatus(
                record.getCredentialId().getBytes(), AdminWebAuthnCredentialStatus.ACTIVE);
        if (existing.isPresent()) {
            existing.orElseThrow().updateUsage(record);
            return;
        }
        credentials.save(AdminWebAuthnCredential.create(adminId(record.getUserEntityUserId()), record));
    }

    @Override
    @Transactional(readOnly = true)
    public CredentialRecord findByCredentialId(Bytes credentialId) {
        return credentials
                .findByCredentialIdAndStatus(
                        credentialId.getBytes(), AdminWebAuthnCredentialStatus.ACTIVE)
                .map(AdminWebAuthnCredential::toCredentialRecord)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CredentialRecord> findByUserId(Bytes userId) {
        return credentials
                .findAllByAdminUserIdAndStatusOrderByCreatedAtAscIdAsc(
                        adminId(userId), AdminWebAuthnCredentialStatus.ACTIVE)
                .stream()
                .map(AdminWebAuthnCredential::toCredentialRecord)
                .toList();
    }

    private static UUID adminId(Bytes userId) {
        var bytes = userId.getBytes();
        if (bytes.length != 16) {
            throw new IllegalArgumentException("WebAuthn user handle is invalid");
        }
        var buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
