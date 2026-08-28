package kr.co.rhaomi.backend.config;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import kr.co.rhaomi.backend.validation.Utf8ByteLength;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap implements ApplicationRunner {

    private final BootstrapAdminProperties properties;
    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;
    private final Environment environment;

    public AdminBootstrap(
            BootstrapAdminProperties properties,
            AdminUserRepository adminUserRepository,
            PasswordEncoder passwordEncoder,
            Validator validator,
            Environment environment) {
        this.properties = properties;
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (!properties.enabled()) {
            return;
        }

        if (environment.acceptsProfiles(Profiles.of("production"))) {
            throw new IllegalStateException("production profile에서는 관리자 bootstrap을 사용할 수 없습니다.");
        }

        var credential = new BootstrapCredential(properties.email(), properties.password());
        if (!validator.validate(credential).isEmpty()) {
            throw new IllegalStateException("관리자 bootstrap 환경변수가 완전하지 않습니다.");
        }

        var normalizedEmail = AdminUser.normalizeEmail(credential.email());
        if (adminUserRepository.findByEmail(normalizedEmail).isPresent()) {
            return;
        }

        adminUserRepository.save(
                AdminUser.create(normalizedEmail, passwordEncoder.encode(credential.password())));
    }

    private record BootstrapCredential(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12) @Utf8ByteLength(max = 72) String password) {}
}
