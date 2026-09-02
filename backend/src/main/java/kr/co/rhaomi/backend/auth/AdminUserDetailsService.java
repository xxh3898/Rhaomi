package kr.co.rhaomi.backend.auth;

import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import kr.co.rhaomi.backend.config.AdminWebAuthnProperties;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository adminUserRepository;
    private final AdminWebAuthnProperties webAuthnProperties;

    public AdminUserDetailsService(
            AdminUserRepository adminUserRepository, AdminWebAuthnProperties webAuthnProperties) {
        this.adminUserRepository = adminUserRepository;
        this.webAuthnProperties = webAuthnProperties;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        var admin = adminUserRepository
                .findByEmail(AdminUser.normalizeEmail(email))
                .orElseThrow(() -> new UsernameNotFoundException("관리자 인증에 실패했습니다."));

        return new AdminPrincipal(
                admin.getId(),
                admin.getEmail(),
                admin.getPasswordHash(),
                admin.getRole(),
                admin.isActive(),
                webAuthnProperties.required()
                        ? AdminAuthenticationStage.FIRST_FACTOR_VERIFIED
                        : AdminAuthenticationStage.SECOND_FACTOR_VERIFIED);
    }
}
