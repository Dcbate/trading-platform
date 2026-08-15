package com.dcbate.tradingplatform.game.repository;

import com.dcbate.tradingplatform.domain.GameSession;
import com.dcbate.tradingplatform.domain.GameStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameSessionRepository extends JpaRepository<GameSession, UUID> {

    Optional<GameSession> findFirstByClientIdAndStatusOrderByStartedAtDesc(String clientId, GameStatus status);

    List<GameSession> findByClientIdAndStatusNot(String clientId, GameStatus status);
}
