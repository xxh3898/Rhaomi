package kr.co.rhaomi.backend.auth;

import org.springframework.security.web.csrf.CsrfToken;

public record CsrfResponse(String headerName, String parameterName, String token) {

    public static CsrfResponse from(CsrfToken csrfToken) {
        return new CsrfResponse(
                csrfToken.getHeaderName(), csrfToken.getParameterName(), csrfToken.getToken());
    }
}
