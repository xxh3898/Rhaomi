package kr.co.rhaomi.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class InitialAdminProvisioningServiceTest {

    private static final String PASSWORD_72_BYTES = "가".repeat(24);
    private static final String PASSWORD_73_BYTES = PASSWORD_72_BYTES + "a";
    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void should_normalizeAndHashCredential_after_transactionLockConfirmsEmptyDatabase() {
        var repository = mock(AdminUserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var lock = mock(InitialAdminProvisioningLock.class);
        when(repository.count()).thenReturn(0L);
        when(encoder.encode(PASSWORD_72_BYTES)).thenReturn("encoded-password");
        var service = service(repository, encoder, lock);

        service.provision(new InitialAdminCredential("ADMIN@example.com", PASSWORD_72_BYTES));

        var ordering = inOrder(lock, repository);
        ordering.verify(lock).acquire();
        ordering.verify(repository).count();
        var admin = ArgumentCaptor.forClass(AdminUser.class);
        verify(repository).saveAndFlush(admin.capture());
        assertEquals("admin@example.com", admin.getValue().getEmail());
        assertEquals("encoded-password", admin.getValue().getPasswordHash());
        assertEquals(72, PASSWORD_72_BYTES.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void should_rejectBeforeLockAndEncoding_when_passwordIs73Utf8Bytes() {
        var repository = mock(AdminUserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var lock = mock(InitialAdminProvisioningLock.class);
        var service = service(repository, encoder, lock);

        var exception = assertThrows(
                InitialAdminProvisioningException.class,
                () -> service.provision(
                        new InitialAdminCredential("admin@example.com", PASSWORD_73_BYTES)));

        assertEquals("INITIAL_ADMIN_INPUT_INVALID", exception.getMessage());
        assertEquals(73, PASSWORD_73_BYTES.getBytes(StandardCharsets.UTF_8).length);
        verify(lock, never()).acquire();
        verify(encoder, never()).encode(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void should_rejectBeforeLockAndEncoding_when_passwordIs11Bytes() {
        var repository = mock(AdminUserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var lock = mock(InitialAdminProvisioningLock.class);
        var service = service(repository, encoder, lock);

        var exception = assertThrows(
                InitialAdminProvisioningException.class,
                () -> service.provision(new InitialAdminCredential("admin@example.com", "12345678901")));

        assertEquals("INITIAL_ADMIN_INPUT_INVALID", exception.getMessage());
        verify(lock, never()).acquire();
        verify(encoder, never()).encode(any());
    }

    @Test
    void should_rejectBeforeLockAndEncoding_when_emailIsInvalid() {
        var repository = mock(AdminUserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var lock = mock(InitialAdminProvisioningLock.class);
        var service = service(repository, encoder, lock);

        var exception = assertThrows(
                InitialAdminProvisioningException.class,
                () -> service.provision(
                        new InitialAdminCredential("not-an-email", "synthetic-password-123!")));

        assertEquals("INITIAL_ADMIN_INPUT_INVALID", exception.getMessage());
        verify(lock, never()).acquire();
        verify(encoder, never()).encode(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void should_rejectBeforeEncoding_when_anyAdministratorAlreadyExists() {
        var repository = mock(AdminUserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var lock = mock(InitialAdminProvisioningLock.class);
        when(repository.count()).thenReturn(1L);
        var service = service(repository, encoder, lock);

        var exception = assertThrows(
                InitialAdminProvisioningException.class,
                () -> service.provision(new InitialAdminCredential(
                        "different@example.com", "synthetic-password-123!")));

        assertEquals("INITIAL_ADMIN_ALREADY_PROVISIONED", exception.getMessage());
        verify(lock).acquire();
        verify(encoder, never()).encode(any());
        verify(repository, never()).saveAndFlush(any());
    }

    private InitialAdminProvisioningService service(
            AdminUserRepository repository,
            PasswordEncoder encoder,
            InitialAdminProvisioningLock lock) {
        return new InitialAdminProvisioningService(
                repository, encoder, VALIDATOR_FACTORY.getValidator(), lock);
    }
}
