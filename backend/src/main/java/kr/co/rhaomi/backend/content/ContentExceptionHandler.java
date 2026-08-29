package kr.co.rhaomi.backend.content;

import kr.co.rhaomi.backend.auth.ApiError;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ContentExceptionHandler {

    @ExceptionHandler(ContentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError handleNotFound() {
        return new ApiError("CONTENT_NOT_FOUND", "콘텐츠를 찾을 수 없습니다.");
    }

    @ExceptionHandler(SlugConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError handleSlugConflict() {
        return new ApiError("SLUG_CONFLICT", "이미 사용 중인 slug입니다.");
    }

    @ExceptionHandler(PublishValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    ApiError handlePublishValidation() {
        return new ApiError("PUBLISH_VALIDATION_FAILED", "게시 필수값을 확인해 주세요.");
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiError handleDataAccessFailure() {
        return new ApiError("INTERNAL_ERROR", "요청을 처리할 수 없습니다.");
    }
}
