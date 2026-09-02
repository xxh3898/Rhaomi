package kr.co.rhaomi.backend.auth.webauthn;

import kr.co.rhaomi.backend.auth.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class AdminWebAuthnExceptionHandler {

    @ExceptionHandler(WebAuthnInvalidRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError invalidRequest() {
        return new ApiError("INVALID_REQUEST", "요청 형식을 확인해 주세요.");
    }

    @ExceptionHandler(WebAuthnVerificationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiError verificationFailed() {
        return new ApiError("WEBAUTHN_VERIFICATION_FAILED", "Passkey 인증을 확인해 주세요.");
    }

    @ExceptionHandler(WebAuthnPolicyException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiError policyViolation() {
        return new ApiError("WEBAUTHN_POLICY_VIOLATION", "Passkey 보안 정책을 확인해 주세요.");
    }
}
