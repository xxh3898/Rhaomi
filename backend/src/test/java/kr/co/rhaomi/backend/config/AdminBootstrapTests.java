package kr.co.rhaomi.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminBootstrapTests {

    private static final String LOCAL_EMAIL = "LOCAL.ADMIN@example.com";
    private static final String LOCAL_PASSWORD = "local-bootstrap-password";
    private static final String PASSWORD_72_BYTES = "가".repeat(24);
    private static final String PASSWORD_73_BYTES = PASSWORD_72_BYTES + "a";

    @Test
    void doesNothingWhenBootstrapIsDisabled() {
        var repository = mock(AdminUserRepository.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        var bootstrap = bootstrap(
                new BootstrapAdminProperties(false, LOCAL_EMAIL, LOCAL_PASSWORD),
                repository,
                passwordEncoder,
                new MockEnvironment());

        bootstrap.run(null);

        verify(repository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void rejectsIncompleteBootstrapCredentials() {
        var repository = mock(AdminUserRepository.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        var bootstrap = bootstrap(
                new BootstrapAdminProperties(true, LOCAL_EMAIL, ""),
                repository,
                passwordEncoder,
                new MockEnvironment());

        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsBootstrapInProductionProfile() {
        var repository = mock(AdminUserRepository.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        var bootstrap = bootstrap(
                new BootstrapAdminProperties(true, LOCAL_EMAIL, LOCAL_PASSWORD),
                repository,
                passwordEncoder,
                environment);

        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        verify(repository, never()).save(any());
    }

    @Test
    void createsNormalizedHashedAdminExactlyOnce() {
        var repository = mock(AdminUserRepository.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        when(repository.findByEmail("local.admin@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(LOCAL_PASSWORD)).thenReturn("encoded-password");
        var bootstrap = bootstrap(
                new BootstrapAdminProperties(true, LOCAL_EMAIL, LOCAL_PASSWORD),
                repository,
                passwordEncoder,
                new MockEnvironment());

        bootstrap.run(null);

        var adminCaptor = ArgumentCaptor.forClass(AdminUser.class);
        verify(repository).save(adminCaptor.capture());
        assertEquals("local.admin@example.com", adminCaptor.getValue().getEmail());
        assertEquals("encoded-password", adminCaptor.getValue().getPasswordHash());
    }

    @Test
    void should_acceptPassword_when_bootstrapInputIs72Utf8Bytes() {
        assertEquals(72, PASSWORD_72_BYTES.getBytes(StandardCharsets.UTF_8).length);
        var repository = mock(AdminUserRepository.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        when(repository.findByEmail("local.admin@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PASSWORD_72_BYTES)).thenReturn("encoded-password");
        var bootstrap = bootstrap(
                new BootstrapAdminProperties(true, LOCAL_EMAIL, PASSWORD_72_BYTES),
                repository,
                passwordEncoder,
                new MockEnvironment());

        bootstrap.run(null);

        verify(passwordEncoder).encode(PASSWORD_72_BYTES);
        verify(repository).save(any(AdminUser.class));
    }

    @Test
    void should_rejectBeforeEncoding_when_bootstrapInputIs73Utf8Bytes() {
        assertEquals(73, PASSWORD_73_BYTES.getBytes(StandardCharsets.UTF_8).length);
        var repository = mock(AdminUserRepository.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        var bootstrap = bootstrap(
                new BootstrapAdminProperties(true, LOCAL_EMAIL, PASSWORD_73_BYTES),
                repository,
                passwordEncoder,
                new MockEnvironment());

        var exception = assertThrows(IllegalStateException.class, () -> bootstrap.run(null));

        assertEquals("관리자 bootstrap 환경변수가 완전하지 않습니다.", exception.getMessage());
        assertNull(exception.getCause());
        verify(passwordEncoder, never()).encode(any());
        verify(repository, never()).save(any());
    }

    private AdminBootstrap bootstrap(
            BootstrapAdminProperties properties,
            AdminUserRepository repository,
            PasswordEncoder passwordEncoder,
            MockEnvironment environment) {
        var validatorFactory = Validation.buildDefaultValidatorFactory();
        return new AdminBootstrap(
                properties,
                repository,
                passwordEncoder,
                validatorFactory.getValidator(),
                environment);
    }
}
