package kr.co.rhaomi.backend.gallery;

import kr.co.rhaomi.backend.auth.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GalleryExceptionHandler {

    @ExceptionHandler(GalleryItemNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError handleNotFound() {
        return new ApiError("GALLERY_ITEM_NOT_FOUND", "갤러리 항목을 찾을 수 없습니다.");
    }

    @ExceptionHandler(GalleryRelationInvalidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    ApiError handleRelationInvalid() {
        return new ApiError("GALLERY_RELATION_INVALID", "갤러리 관계를 확인해 주세요.");
    }

    @ExceptionHandler(GalleryPublishInvalidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    ApiError handlePublishInvalid() {
        return new ApiError("GALLERY_PUBLISH_INVALID", "갤러리 게시 필수값을 확인해 주세요.");
    }

    @ExceptionHandler(GalleryInvalidRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError handleInvalidRequest() {
        return new ApiError("INVALID_REQUEST", "요청 형식을 확인해 주세요.");
    }
}
