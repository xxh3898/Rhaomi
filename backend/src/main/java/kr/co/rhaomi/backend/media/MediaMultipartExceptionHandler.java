package kr.co.rhaomi.backend.media;

import kr.co.rhaomi.backend.auth.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class MediaMultipartExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    ApiError handleTooLarge() {
        return new ApiError("MEDIA_TOO_LARGE", "업로드 파일 크기를 확인해 주세요.");
    }

    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError handleMultipartFailure() {
        return new ApiError("INVALID_REQUEST", "요청 형식을 확인해 주세요.");
    }
}
