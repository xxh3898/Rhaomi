package kr.co.rhaomi.backend.auth.webauthn;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AdminRecoveryCodeRepository extends JpaRepository<AdminRecoveryCode, UUID> {

    boolean existsByAdminUserIdAndUsedAtIsNullAndRevokedAtIsNull(UUID adminUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select code from AdminRecoveryCode code
            where code.adminUserId = :adminUserId
              and code.codeHash = :codeHash
              and code.usedAt is null
              and code.revokedAt is null
            """)
    Optional<AdminRecoveryCode> findActiveForUse(
            @Param("adminUserId") UUID adminUserId, @Param("codeHash") String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select code from AdminRecoveryCode code
            where code.adminUserId = :adminUserId
              and code.usedAt is null
              and code.revokedAt is null
            order by code.createdAt, code.id
            """)
    List<AdminRecoveryCode> findAllActiveForUpdate(@Param("adminUserId") UUID adminUserId);
}
