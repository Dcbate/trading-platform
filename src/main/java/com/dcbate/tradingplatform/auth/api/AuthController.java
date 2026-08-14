package com.dcbate.tradingplatform.auth.api;

import com.dcbate.tradingplatform.auth.api.dto.AuthResponse;
import com.dcbate.tradingplatform.auth.api.dto.LoginRequest;
import com.dcbate.tradingplatform.auth.api.dto.SignupRequest;
import com.dcbate.tradingplatform.auth.service.AuthService;
import com.dcbate.tradingplatform.auth.service.TokenPair;
import com.dcbate.tradingplatform.exception.InvalidRefreshTokenException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues and refreshes the JWTs the rest of the API validates. Tokens are set as HTTP-only,
 * SameSite=Strict cookies — never returned in a JSON body a script could read — so an XSS bug
 * elsewhere in the frontend can't walk off with a session token via localStorage. SameSite=Strict
 * is the primary CSRF defense here (the cookie is never sent on a cross-site request at all,
 * including top-level navigation), which is why this controller doesn't also need a separate CSRF
 * token handshake.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Signup, login, and token refresh")
public class AuthController {

    private static final String ACCESS_COOKIE = "access_token";
    private static final String REFRESH_COOKIE = "refresh_token";

    private final AuthService authService;

    @Value("${app.security.cookie-secure:true}")
    private boolean cookieSecure;

    @PostMapping("/signup")
    @Operation(summary = "Create a user, open their first (CHECKING/USD) account, and log them in")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        return withCookies(authService.signup(request), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with email and password")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return withCookies(authService.login(request), HttpStatus.OK);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange the refresh-token cookie for a new access/refresh pair (rotates the refresh token)")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null) {
            throw new InvalidRefreshTokenException("missing");
        }
        return withCookies(authService.refresh(refreshToken), HttpStatus.OK);
    }

    @PostMapping("/logout")
    @Operation(summary = "Clear the session cookies")
    public ResponseEntity<Void> logout() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, clearCookie(ACCESS_COOKIE).toString());
        headers.add(HttpHeaders.SET_COOKIE, clearCookie(REFRESH_COOKIE).toString());
        return ResponseEntity.noContent().headers(headers).build();
    }

    private ResponseEntity<AuthResponse> withCookies(TokenPair tokens, HttpStatus status) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE,
                buildCookie(ACCESS_COOKIE, tokens.accessToken(), tokens.accessTokenExpiresAt()).toString());
        headers.add(HttpHeaders.SET_COOKIE,
                buildCookie(REFRESH_COOKIE, tokens.refreshToken(), tokens.refreshTokenExpiresAt()).toString());
        return ResponseEntity.status(status).headers(headers).body(tokens.response());
    }

    private ResponseCookie buildCookie(String name, String value, Instant expiresAt) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.between(Instant.now(), expiresAt))
                .build();
    }

    private ResponseCookie clearCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
    }
}
