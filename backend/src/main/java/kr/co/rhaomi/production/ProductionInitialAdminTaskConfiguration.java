package kr.co.rhaomi.production;

import jakarta.validation.Validator;
import java.io.PrintStream;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EntityScan(basePackageClasses = AdminUser.class)
@EnableJpaRepositories(basePackageClasses = AdminUserRepository.class)
class ProductionInitialAdminTaskConfiguration {

    @Bean
    PasswordEncoder initialAdminPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @ConditionalOnMissingBean(name = "initialAdminCredentialSource")
    InitialAdminCredentialSource initialAdminCredentialSource() {
        return new ConsoleInitialAdminCredentialSource();
    }

    @Bean
    @ConditionalOnMissingBean(name = "initialAdminOutput")
    PrintStream initialAdminOutput() {
        return System.out;
    }

    @Bean
    InitialAdminProvisioningLock initialAdminProvisioningLock(JdbcTemplate jdbcTemplate) {
        return new InitialAdminProvisioningLock(jdbcTemplate);
    }

    @Bean
    InitialAdminProvisioningService initialAdminProvisioningService(
            AdminUserRepository adminUserRepository,
            PasswordEncoder passwordEncoder,
            Validator validator,
            InitialAdminProvisioningLock provisioningLock) {
        return new InitialAdminProvisioningService(
                adminUserRepository, passwordEncoder, validator, provisioningLock);
    }

    @Bean
    InitialAdminProvisioningRunner initialAdminProvisioningRunner(
            InitialAdminCredentialSource credentialSource,
            InitialAdminProvisioningService provisioningService,
            PrintStream output) {
        return new InitialAdminProvisioningRunner(
                credentialSource, provisioningService, output);
    }
}
