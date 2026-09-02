package kr.co.rhaomi.backend.auth.webauthn;

import kr.co.rhaomi.backend.auth.AdminAuthenticationStage;

public record AdminWebAuthnStatusResponse(
        boolean required,
        AdminAuthenticationStage authenticationStage,
        long activeCredentialCount,
        boolean initialEnrollmentRequired,
        boolean recoveryCodesAvailable) {}
