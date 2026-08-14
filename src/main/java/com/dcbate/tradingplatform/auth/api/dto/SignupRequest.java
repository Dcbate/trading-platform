package com.dcbate.tradingplatform.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * {@code password} must be 12+ characters with at least one uppercase, one lowercase, one digit,
 * and one symbol — checked here so a weak password never reaches {@code AuthServiceImpl}.
 */
public record SignupRequest(
        @NotBlank @Email String email,
        @NotBlank
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z0-9]).{12,}$",
                message = "password must be at least 12 characters and include an uppercase letter, a lowercase letter, a digit, and a symbol")
        String password) {
}
