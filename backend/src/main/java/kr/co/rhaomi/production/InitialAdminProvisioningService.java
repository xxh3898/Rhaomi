package kr.co.rhaomi.production;

import jakarta.validation.Validator;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

final class InitialAdminProvisioningService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;
    private final InitialAdminProvisioningLock provisioningLock;

    InitialAdminProvisioningService(
            AdminUserRepository adminUserRepository,
            PasswordEncoder passwordEncoder,
            Validator validator,
            InitialAdminProvisioningLock provisioningLock) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
        this.provisioningLock = provisioningLock;
    }

    @Transactional
    void provision(InitialAdminCredential credential) {
        if (credential == null || !validator.validate(credential).isEmpty()) {
            throw new InitialAdminProvisioningException("INITIAL_ADMIN_INPUT_INVALID");
        }

        provisioningLock.acquire();
        if (adminUserRepository.count() != 0) {
            throw new InitialAdminProvisioningException("INITIAL_ADMIN_ALREADY_PROVISIONED");
        }

        var normalizedEmail = AdminUser.normalizeEmail(credential.email());
        var passwordHash = passwordEncoder.encode(credential.password());
        adminUserRepository.saveAndFlush(AdminUser.create(normalizedEmail, passwordHash));
    }
}
