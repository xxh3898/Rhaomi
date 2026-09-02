package kr.co.rhaomi.backend.auth.webauthn;

import java.util.UUID;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import org.springframework.stereotype.Service;

@Service
class AdminAccountSecurityService {

    private final AdminUserRepository adminUsers;

    AdminAccountSecurityService(AdminUserRepository adminUsers) {
        this.adminUsers = adminUsers;
    }

    void requireActive(UUID adminId) {
        requireActive(adminUsers.findById(adminId).orElse(null));
    }

    void requireActiveForUpdate(UUID adminId) {
        requireActive(adminUsers.findByIdForUpdate(adminId).orElse(null));
    }

    private static void requireActive(AdminUser admin) {
        if (admin == null || !admin.isActive()) {
            throw new WebAuthnPolicyException();
        }
    }
}
