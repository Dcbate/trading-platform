package com.dcbate.tradingplatform.game.repository;

import com.dcbate.tradingplatform.domain.GamePosition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GamePositionRepository extends JpaRepository<GamePosition, UUID> {

    Optional<GamePosition> findBySessionIdAndSymbol(UUID sessionId, String symbol);

    List<GamePosition> findBySessionId(UUID sessionId);
}
