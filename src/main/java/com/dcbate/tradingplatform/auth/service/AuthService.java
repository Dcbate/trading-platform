package com.dcbate.tradingplatform.auth.service;

import com.dcbate.tradingplatform.auth.api.dto.LoginRequest;
import com.dcbate.tradingplatform.auth.api.dto.SignupRequest;

/**
 * Issues the JWTs {@code SecurityConfig}'s resource server validates. Before this, nothing in the
 * codebase actually minted a token for a real user — {@code CallerPrincipal}/{@code requireOwner}
 * enforced ownership on whatever {@code clientId} a JWT carried, but the only way to get one was a
 * test hand-signing it. This is the missing issuing half.
 */
public interface AuthService {

    /** Creates the user, opens their first account (a zero-balance {@code CHECKING} account in USD), and logs them in. */
    TokenPair signup(SignupRequest request);

    TokenPair login(LoginRequest request);

    /** Redeems a refresh token exactly once: the redeemed row is revoked and a new pair is issued (rotation), so reusing an old refresh token fails outright. */
    TokenPair refresh(String refreshToken);

    /** Best-effort revocation of the presented refresh token; never throws — a missing, expired, or already-revoked token simply has nothing left to revoke. */
    void logout(String refreshToken);
}
