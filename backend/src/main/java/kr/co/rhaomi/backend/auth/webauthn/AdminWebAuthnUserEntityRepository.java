package kr.co.rhaomi.backend.auth.webauthn;

import java.nio.ByteBuffer;
import java.util.UUID;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.stereotype.Component;

@Component
class AdminWebAuthnUserEntityRepository implements PublicKeyCredentialUserEntityRepository {

    private final AdminUserRepository adminUsers;

    AdminWebAuthnUserEntityRepository(AdminUserRepository adminUsers) {
        this.adminUsers = adminUsers;
    }

    @Override
    public PublicKeyCredentialUserEntity findById(Bytes id) {
        var bytes = id.getBytes();
        if (bytes.length != 16) {
            return null;
        }
        var buffer = ByteBuffer.wrap(bytes);
        return adminUsers
                .findById(new UUID(buffer.getLong(), buffer.getLong()))
                .filter(AdminUser::isActive)
                .map(AdminWebAuthnUserEntityRepository::entity)
                .orElse(null);
    }

    @Override
    public PublicKeyCredentialUserEntity findByUsername(String username) {
        return adminUsers
                .findByEmail(AdminUser.normalizeEmail(username))
                .filter(AdminUser::isActive)
                .map(AdminWebAuthnUserEntityRepository::entity)
                .orElse(null);
    }

    @Override
    public void save(PublicKeyCredentialUserEntity userEntity) {
        var stored = findById(userEntity.getId());
        if (stored == null || !stored.getName().equals(userEntity.getName())) {
            throw new IllegalArgumentException("WebAuthn user entity does not match an active admin");
        }
    }

    @Override
    public void delete(Bytes id) {
        throw new UnsupportedOperationException("Admin WebAuthn user entity deletion is forbidden");
    }

    static Bytes userHandle(UUID id) {
        return new Bytes(ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array());
    }

    private static PublicKeyCredentialUserEntity entity(AdminUser admin) {
        return ImmutablePublicKeyCredentialUserEntity.builder()
                .id(userHandle(admin.getId()))
                .name(admin.getEmail())
                .displayName("Rhaomi 관리자")
                .build();
    }
}
