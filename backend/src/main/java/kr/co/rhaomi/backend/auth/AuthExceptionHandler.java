package kr.co.rhaomi.backend.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidAdminCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiError handleInvalidCredentials() {
        return new ApiError("INVALID_CREDENTIALS", "이메일 또는 비밀번호를 확인해 주세요.");
    }

    @ExceptionHandler(LoginRateLimitExceededException.class)
    ResponseEntity<ApiError> handleLoginRateLimit(LoginRateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(new ApiError(
                        "LOGIN_RATE_LIMITED",
                        "로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요."));
    }

    @ExceptionHandler(AuthenticationServiceException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiError handleAuthenticationServiceFailure() {
        return new ApiError("AUTH_SERVICE_UNAVAILABLE", "인증 서비스를 사용할 수 없습니다.");
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError handleValidationFailure() {
        return new ApiError("INVALID_REQUEST", "요청 형식을 확인해 주세요.");
    }
}
