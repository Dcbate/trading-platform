package com.dcbate.tradingplatform.auth.repository;

import com.dcbate.tradingplatform.domain.RefreshToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenIdAndRevokedFalse(UUID tokenId);
}
