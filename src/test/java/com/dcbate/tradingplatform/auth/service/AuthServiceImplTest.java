package com.dcbate.tradingplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.account.api.dto.AccountRequest;
import com.dcbate.tradingplatform.account.api.dto.AccountResponse;
import com.dcbate.tradingplatform.account.service.AccountService;
import com.dcbate.tradingplatform.auth.api.dto.LoginRequest;
import com.dcbate.tradingplatform.auth.api.dto.SignupRequest;
import com.dcbate.tradingplatform.auth.repository.RefreshTokenRepository;
import com.dcbate.tradingplatform.auth.repository.UserRepository;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.AccountType;
import com.dcbate.tradingplatform.domain.RefreshToken;
import com.dcbate.tradingplatform.domain.User;
import com.dcbate.tradingplatform.exception.EmailAlreadyRegisteredException;
import com.dcbate.tradingplatform.exception.InvalidCredentialsException;
import com.dcbate.tradingplatform.exception.InvalidRefreshTokenException;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.security.JwtIssuer;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtIssuer jwtIssuer;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(userRepository, refreshTokenRepository, accountService, passwordEncoder, jwtIssuer);
    }

    private void stubTokenIssuance() {
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtIssuer.issueAccessToken(anyString(), any(Instant.class))).thenReturn("access-jwt");
        when(jwtIssuer.issueRefreshToken(anyString(), any(UUID.class), any(Instant.class))).thenReturn("refresh-jwt");
    }

    @Test
    void signupCreatesUserOpensCheckingAccountAndIssuesTokens() {
        stubTokenIssuance();
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Str0ng!Passw0rd")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.openAccount(any(AccountRequest.class), any(CallerPrincipal.class)))
                .thenReturn(new AccountResponse(UUID.randomUUID(), "ignored", AccountType.CHECKING, "GBP", null,
                        java.math.BigDecimal.ZERO, AccountStatus.ACTIVE, Instant.now()));

        TokenPair tokens = authService.signup(new SignupRequest("new@example.com", "Str0ng!Passw0rd"));

        assertThat(tokens.response().email()).isEqualTo("new@example.com");
        assertThat(tokens.accessToken()).isEqualTo("access-jwt");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-jwt");

        var accountRequestCaptor = org.mockito.ArgumentCaptor.forClass(AccountRequest.class);
        verify(accountService).openAccount(accountRequestCaptor.capture(), any(CallerPrincipal.class));
        assertThat(accountRequestCaptor.getValue().accountType()).isEqualTo(AccountType.CHECKING);
        assertThat(accountRequestCaptor.getValue().currency()).isEqualTo("GBP");
        assertThat(accountRequestCaptor.getValue().clientId()).isEqualTo(tokens.response().clientId());
    }

    @Test
    void signupRejectsAnAlreadyRegisteredEmail() {
        when(userRepository.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(new SignupRequest("taken@example.com", "Str0ng!Passw0rd")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        stubTokenIssuance();
        UUID userId = UUID.randomUUID();
        User user = User.builder().userId(userId).email("client@example.com").passwordHash("hashed").createdAt(Instant.now()).build();
        when(userRepository.findByEmailIgnoreCase("client@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "hashed")).thenReturn(true);

        TokenPair tokens = authService.login(new LoginRequest("client@example.com", "correct-password"));

        assertThat(tokens.response().clientId()).isEqualTo(userId.toString());
    }

    @Test
    void loginRejectsAWrongPasswordWithoutRevealingWhichFieldWasWrong() {
        User user = User.builder().userId(UUID.randomUUID()).email("client@example.com").passwordHash("hashed").createdAt(Instant.now()).build();
        when(userRepository.findByEmailIgnoreCase("client@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("client@example.com", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginRejectsAnUnknownEmailWithTheSameExceptionAsAWrongPassword() {
        when(userRepository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshRotatesTheTokenAndRevokesTheOldOne() {
        stubTokenIssuance();
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        User user = User.builder().userId(userId).email("client@example.com").passwordHash("hashed").createdAt(Instant.now()).build();
        RefreshToken stored = RefreshToken.builder()
                .tokenId(tokenId).userId(userId).expiresAt(Instant.now().plusSeconds(3600)).revoked(false).createdAt(Instant.now())
                .build();

        when(jwtIssuer.verifyRefreshToken("old-refresh-jwt")).thenReturn(tokenId);
        when(refreshTokenRepository.findByTokenIdAndRevokedFalse(tokenId)).thenReturn(Optional.of(stored));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        authService.refresh("old-refresh-jwt");

        assertThat(stored.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(stored);
    }

    @Test
    void refreshRejectsAnAlreadyRevokedToken() {
        UUID tokenId = UUID.randomUUID();
        when(jwtIssuer.verifyRefreshToken("reused-jwt")).thenReturn(tokenId);
        when(refreshTokenRepository.findByTokenIdAndRevokedFalse(tokenId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("reused-jwt"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void logoutRevokesTheRefreshToken() {
        UUID tokenId = UUID.randomUUID();
        RefreshToken stored = RefreshToken.builder()
                .tokenId(tokenId).userId(UUID.randomUUID()).expiresAt(Instant.now().plusSeconds(3600)).revoked(false).createdAt(Instant.now())
                .build();
        when(jwtIssuer.verifyRefreshToken("current-refresh-jwt")).thenReturn(tokenId);
        when(refreshTokenRepository.findByTokenIdAndRevokedFalse(tokenId)).thenReturn(Optional.of(stored));

        authService.logout("current-refresh-jwt");

        assertThat(stored.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(stored);
    }

    @Test
    void logoutIsANoOpWhenNoCookieWasPresent() {
        authService.logout(null);

        verify(refreshTokenRepository, org.mockito.Mockito.never()).findByTokenIdAndRevokedFalse(any());
    }

    @Test
    void logoutSucceedsSilentlyForAnAlreadyExpiredOrMalformedToken() {
        when(jwtIssuer.verifyRefreshToken("garbage")).thenThrow(new InvalidRefreshTokenException("malformed token"));

        authService.logout("garbage");

        verify(refreshTokenRepository, org.mockito.Mockito.never()).save(any());
    }
}
