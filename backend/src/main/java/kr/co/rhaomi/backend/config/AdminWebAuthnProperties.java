package kr.co.rhaomi.backend.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rhaomi.admin-auth.webauthn")
public record AdminWebAuthnProperties(
        boolean required, String rpId, String origin, String rpName, Duration challengeTtl) {

    private static final Duration MINIMUM_CHALLENGE_TTL = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_CHALLENGE_TTL = Duration.ofMinutes(10);

    public void validateProduction() {
        if (!required) {
            throw new IllegalStateException("production profile에서는 WebAuthn 2차 인증이 필요합니다.");
        }
        if (isBlank(rpId) || rpId.contains("*") || isBlank(rpName)) {
            throw new IllegalStateException("production WebAuthn RP 설정을 확인해 주세요.");
        }
        if (challengeTtl == null
                || challengeTtl.compareTo(MINIMUM_CHALLENGE_TTL) < 0
                || challengeTtl.compareTo(MAXIMUM_CHALLENGE_TTL) > 0) {
            throw new IllegalStateException("production WebAuthn challenge TTL을 확인해 주세요.");
        }

        final URI parsedOrigin;
        try {
            parsedOrigin = URI.create(origin);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("production WebAuthn origin을 확인해 주세요.");
        }
        var host = parsedOrigin.getHost();
        if (!"https".equals(parsedOrigin.getScheme())
                || isBlank(host)
                || parsedOrigin.getRawUserInfo() != null
                || parsedOrigin.getRawQuery() != null
                || parsedOrigin.getRawFragment() != null
                || !(parsedOrigin.getRawPath() == null || parsedOrigin.getRawPath().isEmpty())
                || (parsedOrigin.getPort() != -1 && parsedOrigin.getPort() != 443)
                || !(host.equals(rpId) || host.endsWith("." + rpId))) {
            throw new IllegalStateException("production WebAuthn origin을 확인해 주세요.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
