package kr.co.rhaomi.backend.config;

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

    public ProductionSecurityGuard(
            Environment environment,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureSessionCookie) {
        this.environment = environment;
        this.secureSessionCookie = secureSessionCookie;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (environment.acceptsProfiles(Profiles.of("production")) && !secureSessionCookie) {
            throw new IllegalStateException(
                    "production profile에서는 Secure session cookie가 필요합니다.");
        }
    }
}
