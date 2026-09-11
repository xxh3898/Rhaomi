package kr.co.rhaomi.production;

import java.io.Console;
import java.util.Arrays;
import java.util.function.Supplier;

final class ConsoleInitialAdminCredentialSource implements InitialAdminCredentialSource {

    private final Supplier<Console> consoleSupplier;

    ConsoleInitialAdminCredentialSource() {
        this(System::console);
    }

    ConsoleInitialAdminCredentialSource(Supplier<Console> consoleSupplier) {
        this.consoleSupplier = consoleSupplier;
    }

    @Override
    public InitialAdminCredential read() {
        var console = consoleSupplier.get();
        if (console == null) {
            throw new InitialAdminProvisioningException(
                    "INITIAL_ADMIN_INTERACTIVE_TERMINAL_REQUIRED");
        }

        var email = console.readLine("관리자 email: ");
        char[] password = null;
        char[] confirmation = null;
        try {
            password = console.readPassword("관리자 password: ");
            confirmation = console.readPassword("관리자 password 확인: ");
            if (email == null
                    || password == null
                    || confirmation == null
                    || !Arrays.equals(password, confirmation)) {
                throw new InitialAdminProvisioningException("INITIAL_ADMIN_INPUT_INVALID");
            }
            return new InitialAdminCredential(email, new String(password));
        } finally {
            clear(password);
            clear(confirmation);
        }
    }

    private void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
