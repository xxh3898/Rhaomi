package kr.co.rhaomi.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.rhaomi.backend.validation.Utf8ByteLength;

public record LoginRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Utf8ByteLength(max = 72) String password) {}
