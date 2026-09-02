package kr.co.rhaomi.backend.auth.webauthn;

import java.util.List;

public record RecoveryCodesResponse(List<String> recoveryCodes) {}
