package kr.co.rhaomi.backend.config;

import kr.co.rhaomi.backend.build.BuildServiceProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class ProductionSecurityGuard implements ApplicationRunner {

    private final Environment environment;
    private final boolean secureSessionCookie;
    private final BuildServiceProperties buildServiceProperties;
    private final AdminWebAuthnProperties webAuthnProperties;

    public ProductionSecurityGuard(
            Environment environment,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureSessionCookie,
            BuildServiceProperties buildServiceProperties,
            AdminWebAuthnProperties webAuthnProperties) {
        this.environment = environment;
        this.secureSessionCookie = secureSessionCookie;
        this.buildServiceProperties = buildServiceProperties;
        this.webAuthnProperties = webAuthnProperties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (environment.acceptsProfiles(Profiles.of("production"))) {
            if (!secureSessionCookie) {
                throw new IllegalStateException(
                        "production profile에서는 Secure session cookie가 필요합니다.");
            }
            if (!buildServiceProperties.isConfigured()) {
                throw new IllegalStateException(
                        "production profile에서는 build service token이 필요합니다.");
            }
            webAuthnProperties.validateProduction();
        }
    }
}
