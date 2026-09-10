package kr.co.rhaomi.production;

import java.io.PrintStream;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

final class InitialAdminProvisioningRunner implements ApplicationRunner {

    private static final String SUCCESS_RESULT =
            "{\"contract\":\"rhaomi-initial-admin-v1\","
                    + "\"status\":\"success\",\"administratorCount\":1}";

    private final InitialAdminCredentialSource credentialSource;
    private final InitialAdminProvisioningService provisioningService;
    private final PrintStream output;

    InitialAdminProvisioningRunner(
            InitialAdminCredentialSource credentialSource,
            InitialAdminProvisioningService provisioningService,
            PrintStream output) {
        this.credentialSource = credentialSource;
        this.provisioningService = provisioningService;
        this.output = output;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            provisioningService.provision(credentialSource.read());
        } catch (InitialAdminProvisioningException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InitialAdminProvisioningException("INITIAL_ADMIN_PROVISIONING_FAILED");
        }
        output.println(SUCCESS_RESULT);
        output.flush();
    }
}
