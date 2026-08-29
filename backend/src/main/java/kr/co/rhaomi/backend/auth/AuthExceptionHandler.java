package kr.co.rhaomi.backend.auth;

import org.springframework.http.HttpStatus;
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
