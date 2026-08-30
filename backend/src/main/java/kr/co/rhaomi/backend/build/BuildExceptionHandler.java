package kr.co.rhaomi.backend.build;

import kr.co.rhaomi.backend.auth.ApiError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BuildController.class)
public class BuildExceptionHandler {

    @ExceptionHandler({
        BuildInvalidRequestException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError handleInvalidRequest() {
        return new ApiError("INVALID_REQUEST", "요청 형식을 확인해 주세요.");
    }

    @ExceptionHandler(BuildGenerationNotActiveException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError handleGenerationNotActive() {
        return new ApiError("BUILD_GENERATION_NOT_ACTIVE", "활성 빌드 generation이 아닙니다.");
    }

    @ExceptionHandler(BuildSnapshotInvalidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    ApiError handleSnapshotInvalid() {
        return new ApiError("BUILD_SNAPSHOT_INVALID", "공개 snapshot을 생성할 수 없습니다.");
    }

    @ExceptionHandler(BuildMediaNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError handleMediaNotFound() {
        return new ApiError("BUILD_MEDIA_NOT_FOUND", "빌드 미디어를 찾을 수 없습니다.");
    }

    @ExceptionHandler(BuildMediaUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiError handleMediaUnavailable() {
        return new ApiError("BUILD_MEDIA_UNAVAILABLE", "빌드 미디어를 사용할 수 없습니다.");
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiError handleDataAccessFailure() {
        return new ApiError("BUILD_INTERNAL_ERROR", "빌드 요청을 처리할 수 없습니다.");
    }
}
