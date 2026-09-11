package kr.co.rhaomi.production;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.rhaomi.backend.validation.Utf8ByteLength;

record InitialAdminCredential(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 12) @Utf8ByteLength(max = 72) String password) {

    @Override
    public String toString() {
        return "InitialAdminCredential[REDACTED]";
    }
}
