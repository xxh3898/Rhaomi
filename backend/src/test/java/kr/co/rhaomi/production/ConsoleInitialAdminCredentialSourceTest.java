package kr.co.rhaomi.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.Console;
import org.junit.jupiter.api.Test;

class ConsoleInitialAdminCredentialSourceTest {

    @Test
    void should_readConfirmedPasswordAndClearConsoleBuffers_when_interactiveTerminalExists() {
        var console = mock(Console.class);
        var password = "synthetic-password-123!".toCharArray();
        var confirmation = password.clone();
        when(console.readLine("관리자 email: ")).thenReturn("ADMIN@example.com");
        when(console.readPassword("관리자 password: ")).thenReturn(password);
        when(console.readPassword("관리자 password 확인: ")).thenReturn(confirmation);
        var source = new ConsoleInitialAdminCredentialSource(() -> console);

        var credential = source.read();

        assertEquals("ADMIN@example.com", credential.email());
        assertEquals("synthetic-password-123!", credential.password());
        assertEquals("InitialAdminCredential[REDACTED]", credential.toString());
        assertTrue(allCleared(password));
        assertTrue(allCleared(confirmation));
        assertFalse(credential.toString().contains(credential.password()));
        assertFalse(credential.toString().contains(credential.email()));
    }

    @Test
    void should_rejectWithoutCredentialDetail_when_passwordConfirmationDiffers() {
        var console = mock(Console.class);
        var password = "synthetic-password-123!".toCharArray();
        var confirmation = "different-password-123!".toCharArray();
        when(console.readLine("관리자 email: ")).thenReturn("admin@example.com");
        when(console.readPassword("관리자 password: ")).thenReturn(password);
        when(console.readPassword("관리자 password 확인: ")).thenReturn(confirmation);
        var source = new ConsoleInitialAdminCredentialSource(() -> console);

        var exception = assertThrows(InitialAdminProvisioningException.class, source::read);

        assertEquals("INITIAL_ADMIN_INPUT_INVALID", exception.getMessage());
        assertTrue(allCleared(password));
        assertTrue(allCleared(confirmation));
    }

    @Test
    void should_failClosed_when_interactiveTerminalIsUnavailable() {
        var source = new ConsoleInitialAdminCredentialSource(() -> null);

        var exception = assertThrows(InitialAdminProvisioningException.class, source::read);

        assertEquals("INITIAL_ADMIN_INTERACTIVE_TERMINAL_REQUIRED", exception.getMessage());
    }

    @Test
    void should_clearFirstPasswordBuffer_when_confirmationReadFails() {
        var console = mock(Console.class);
        var password = "synthetic-password-123!".toCharArray();
        when(console.readLine("관리자 email: ")).thenReturn("admin@example.com");
        when(console.readPassword("관리자 password: ")).thenReturn(password);
        when(console.readPassword("관리자 password 확인: "))
                .thenThrow(new IllegalStateException("synthetic console failure"));
        var source = new ConsoleInitialAdminCredentialSource(() -> console);

        assertThrows(IllegalStateException.class, source::read);

        assertTrue(allCleared(password));
    }

    private boolean allCleared(char[] value) {
        for (var character : value) {
            if (character != '\0') {
                return false;
            }
        }
        return true;
    }
}
