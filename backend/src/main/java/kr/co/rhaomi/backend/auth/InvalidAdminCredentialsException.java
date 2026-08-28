package kr.co.rhaomi.backend.auth;

public class InvalidAdminCredentialsException extends RuntimeException {

    public InvalidAdminCredentialsException() {
        super("관리자 인증에 실패했습니다.");
    }
}
