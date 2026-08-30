package kr.co.rhaomi.backend.build;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

final class BuildSecurityResponses {

    private BuildSecurityResponses() {}

    static void unauthorized(HttpServletResponse response) throws IOException {
        write(response, 401, "BUILD_UNAUTHORIZED", "빌드 서비스 인증이 필요합니다.");
    }

    static void unavailable(HttpServletResponse response) throws IOException {
        write(response, 503, "BUILD_SERVICE_UNAVAILABLE", "빌드 서비스를 사용할 수 없습니다.");
    }

    static void forbidden(HttpServletResponse response) throws IOException {
        write(response, 403, "BUILD_FORBIDDEN", "허용되지 않은 빌드 서비스 요청입니다.");
    }

    private static void write(
            HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
