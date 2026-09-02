package kr.co.rhaomi.backend.media;

import kr.co.rhaomi.backend.auth.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice(assignableTypes = MediaAdminController.class)
public class MediaExceptionHandler {

    @ExceptionHandler({MediaInvalidRequestException.class, MissingServletRequestPartException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError handleInvalidRequest() {
        return new ApiError("INVALID_REQUEST", "요청 형식을 확인해 주세요.");
    }

    @ExceptionHandler(MediaTooLargeException.class)
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    ApiError handleTooLarge() {
        return new ApiError("MEDIA_TOO_LARGE", "업로드 파일 크기를 확인해 주세요.");
    }

    @ExceptionHandler({MediaTypeUnsupportedException.class, HttpMediaTypeNotSupportedException.class})
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    ApiError handleUnsupportedType() {
        return new ApiError("MEDIA_TYPE_UNSUPPORTED", "지원하지 않는 이미지 형식입니다.");
    }

    @ExceptionHandler(MediaInvalidImageException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    ApiError handleInvalidImage() {
        return new ApiError("MEDIA_INVALID_IMAGE", "이미지 파일을 처리할 수 없습니다.");
    }

    @ExceptionHandler(MediaNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError handleNotFound() {
        return new ApiError("MEDIA_NOT_FOUND", "미디어를 찾을 수 없습니다.");
    }

    @ExceptionHandler(MediaProcessorUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiError handleProcessorUnavailable() {
        return new ApiError("MEDIA_PROCESSOR_UNAVAILABLE", "이미지 처리기를 사용할 수 없습니다.");
    }

    @ExceptionHandler(MediaStorageException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiError handleStorageFailure() {
        return new ApiError("INTERNAL_ERROR", "요청을 처리할 수 없습니다.");
    }

}
