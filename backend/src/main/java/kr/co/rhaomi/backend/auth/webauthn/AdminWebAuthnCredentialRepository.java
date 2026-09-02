package kr.co.rhaomi.backend.auth.webauthn;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AdminWebAuthnCredentialRepository extends JpaRepository<AdminWebAuthnCredential, UUID> {

    Optional<AdminWebAuthnCredential> findByCredentialIdAndStatus(
            byte[] credentialId, AdminWebAuthnCredentialStatus status);

    List<AdminWebAuthnCredential> findAllByAdminUserIdAndStatusOrderByCreatedAtAscIdAsc(
            UUID adminUserId, AdminWebAuthnCredentialStatus status);

    Optional<AdminWebAuthnCredential> findByIdAndAdminUserIdAndStatus(
            UUID id, UUID adminUserId, AdminWebAuthnCredentialStatus status);

    long countByAdminUserIdAndStatus(UUID adminUserId, AdminWebAuthnCredentialStatus status);
}
