package com.dcbate.tradingplatform.auth.service;

import com.dcbate.tradingplatform.account.api.dto.AccountRequest;
import com.dcbate.tradingplatform.account.service.AccountService;
import com.dcbate.tradingplatform.auth.api.dto.AuthResponse;
import com.dcbate.tradingplatform.auth.api.dto.LoginRequest;
import com.dcbate.tradingplatform.auth.api.dto.SignupRequest;
import com.dcbate.tradingplatform.auth.repository.RefreshTokenRepository;
import com.dcbate.tradingplatform.auth.repository.UserRepository;
import com.dcbate.tradingplatform.domain.AccountType;
import com.dcbate.tradingplatform.domain.RefreshToken;
import com.dcbate.tradingplatform.domain.User;
import com.dcbate.tradingplatform.exception.EmailAlreadyRegisteredException;
import com.dcbate.tradingplatform.exception.InvalidCredentialsException;
import com.dcbate.tradingplatform.exception.InvalidRefreshTokenException;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.security.JwtIssuer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** @see AuthService */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccountService accountService;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;

    @Value("${jwt.access-token-ttl-minutes:15}")
    private long accessTokenTtlMinutes = 15;

    @Value("${jwt.refresh-token-ttl-days:7}")
    private long refreshTokenTtlDays = 7;

    @Override
    @Transactional
    public TokenPair signup(SignupRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new EmailAlreadyRegisteredException(request.email());
        }

        User user = User.builder()
                .userId(UUID.randomUUID())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .createdAt(Instant.now())
                .build();
        userRepository.save(user);
        String clientId = user.getUserId().toString();

        accountService.openAccount(
                new AccountRequest(clientId, AccountType.CHECKING, "USD", null, BigDecimal.ZERO),
                new CallerPrincipal(clientId, false));

        log.info("User signed up: userId={}, email={}", user.getUserId(), user.getEmail());
        return issueTokens(clientId, user.getEmail());
    }

    @Override
    public TokenPair login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueTokens(user.getUserId().toString(), user.getEmail());
    }

    @Override
    @Transactional
    public TokenPair refresh(String refreshToken) {
        UUID tokenId = jwtIssuer.verifyRefreshToken(refreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenIdAndRevokedFalse(tokenId)
                .orElseThrow(() -> new InvalidRefreshTokenException("unknown or already used"));
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException("expired");
        }
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new InvalidRefreshTokenException("user no longer exists"));
        return issueTokens(user.getUserId().toString(), user.getEmail());
    }

    private TokenPair issueTokens(String clientId, String email) {
        Instant accessExpiresAt = Instant.now().plus(Duration.ofMinutes(accessTokenTtlMinutes));
        Instant refreshExpiresAt = Instant.now().plus(Duration.ofDays(refreshTokenTtlDays));
        UUID refreshTokenId = UUID.randomUUID();

        refreshTokenRepository.save(RefreshToken.builder()
                .tokenId(refreshTokenId)
                .userId(UUID.fromString(clientId))
                .expiresAt(refreshExpiresAt)
                .revoked(false)
                .createdAt(Instant.now())
                .build());

        String accessToken = jwtIssuer.issueAccessToken(clientId, accessExpiresAt);
        String refreshToken = jwtIssuer.issueRefreshToken(clientId, refreshTokenId, refreshExpiresAt);
        return new TokenPair(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt, new AuthResponse(clientId, email));
    }
}
