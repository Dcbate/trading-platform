package com.dcbate.tradingplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tracks issued refresh tokens so a used or stolen one can be revoked instead of trusting the JWT
 * signature alone for a week-long lifetime. {@code tokenId} is the refresh JWT's {@code jti}
 * claim; {@link com.dcbate.tradingplatform.auth.service.AuthServiceImpl#refresh} marks a row
 * revoked the moment it's redeemed, so a refresh token can only ever be used once — reusing one
 * (a stolen-token signal) fails outright rather than silently succeeding.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    private UUID tokenId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(nullable = false)
    private Instant createdAt;
}
