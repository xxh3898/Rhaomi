package kr.co.rhaomi.backend.build;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rhaomi.build-service")
public final class BuildServiceProperties {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final byte[] DISABLED_TOKEN = new byte[64];

    private final boolean configured;
    private final byte[] expectedToken;

    public BuildServiceProperties(String token) {
        configured = token != null && TOKEN_PATTERN.matcher(token).matches();
        expectedToken = configured
                ? token.getBytes(StandardCharsets.US_ASCII)
                : DISABLED_TOKEN.clone();
    }

    public boolean isConfigured() {
        return configured;
    }

    boolean matches(String candidate) {
        var candidateValid = candidate != null && TOKEN_PATTERN.matcher(candidate).matches();
        var candidateBytes = candidateValid
                ? candidate.getBytes(StandardCharsets.US_ASCII)
                : DISABLED_TOKEN;
        return configured && candidateValid && MessageDigest.isEqual(expectedToken, candidateBytes);
    }

    @Override
    public String toString() {
        return "BuildServiceProperties[configured=" + configured + "]";
    }
}
