package kr.co.rhaomi.backend.auth.webauthn;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.co.rhaomi.backend.auth.AdminAuthenticationStage;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class AdminRecoveryCodeService {

    private static final int RECOVERY_CODE_COUNT = 10;
    private static final Pattern RECOVERY_CODE_PATTERN =
            Pattern.compile("^[0-9a-f]{8}(?:-[0-9a-f]{8}){3}$");
    private static final HexFormat HEX = HexFormat.of();

    private final AdminRecoveryCodeRepository recoveryCodes;
    private final AdminAuthenticationStageService authenticationStages;
    private final AdminWebAuthnService webAuthnService;
    private final AdminAccountSecurityService accountSecurity;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final SecureRandom secureRandom = new SecureRandom();

    AdminRecoveryCodeService(
            AdminRecoveryCodeRepository recoveryCodes,
            AdminAuthenticationStageService authenticationStages,
            AdminWebAuthnService webAuthnService,
            AdminAccountSecurityService accountSecurity,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.recoveryCodes = recoveryCodes;
        this.authenticationStages = authenticationStages;
        this.webAuthnService = webAuthnService;
        this.accountSecurity = accountSecurity;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    AdminWebAuthnStatusResponse verify(
            Authentication authentication,
            RecoveryCodeRequest requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        var principal = AdminAuthenticationStageService.principal(authentication);
        if (principal.authenticationStage() != AdminAuthenticationStage.FIRST_FACTOR_VERIFIED) {
            throw new WebAuthnPolicyException();
        }
        var canonicalCode = canonicalCode(requestBody == null ? null : requestBody.code());
        transactions.executeWithoutResult(ignored -> {
            accountSecurity.requireActiveForUpdate(principal.id());
            var matched = recoveryCodes
                    .findActiveForUse(principal.id(), hash(canonicalCode))
                    .orElseThrow(WebAuthnVerificationException::new);
            var now = clock.instant();
            for (var code : recoveryCodes.findAllActiveForUpdate(principal.id())) {
                if (code.codeHash().equals(matched.codeHash())) {
                    code.use(now);
                } else {
                    code.revoke(now);
                }
            }
        });
        var restricted = authenticationStages.promote(
                authentication,
                AdminAuthenticationStage.RECOVERY_ROTATION_REQUIRED,
                request,
                response);
        return webAuthnService.status(restricted);
    }

    RecoveryCodesResponse rotate(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        var principal = AdminAuthenticationStageService.principal(authentication);
        if (principal.authenticationStage() != AdminAuthenticationStage.SECOND_FACTOR_VERIFIED
                && principal.authenticationStage()
                        != AdminAuthenticationStage.RECOVERY_ROTATION_REQUIRED) {
            throw new WebAuthnPolicyException();
        }
        var plaintextCodes = Objects.requireNonNull(transactions.execute(ignored -> {
            accountSecurity.requireActiveForUpdate(principal.id());
            var now = clock.instant();
            recoveryCodes.findAllActiveForUpdate(principal.id()).forEach(code -> code.revoke(now));
            var setId = UUID.randomUUID();
            var generatedCodes = new ArrayList<String>(RECOVERY_CODE_COUNT);
            var entities = new ArrayList<AdminRecoveryCode>(RECOVERY_CODE_COUNT);
            for (var index = 0; index < RECOVERY_CODE_COUNT; index++) {
                var plaintext = generateCode();
                generatedCodes.add(plaintext);
                entities.add(AdminRecoveryCode.create(principal.id(), setId, hash(plaintext), now));
            }
            recoveryCodes.saveAll(entities);
            return List.copyOf(generatedCodes);
        }));
        authenticationStages.promote(
                authentication,
                AdminAuthenticationStage.SECOND_FACTOR_VERIFIED,
                request,
                response);
        return new RecoveryCodesResponse(plaintextCodes);
    }

    private String generateCode() {
        var bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        var hex = HEX.formatHex(bytes);
        return hex.substring(0, 8)
                + "-"
                + hex.substring(8, 16)
                + "-"
                + hex.substring(16, 24)
                + "-"
                + hex.substring(24, 32);
    }

    private static String canonicalCode(String value) {
        if (value == null || !RECOVERY_CODE_PATTERN.matcher(value).matches()) {
            throw new WebAuthnVerificationException();
        }
        return value;
    }

    private static String hash(String code) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
