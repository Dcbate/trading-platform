package com.dcbate.tradingplatform.auth.service;

import com.dcbate.tradingplatform.auth.api.dto.AuthResponse;
import java.time.Instant;

/** What {@code AuthController} needs to set both cookies and return the body — never serialized as-is. */
public record TokenPair(
        String accessToken, Instant accessTokenExpiresAt,
        String refreshToken, Instant refreshTokenExpiresAt,
        AuthResponse response) {
}
