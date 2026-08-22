package com.dcbate.tradingplatform.game.repository;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import com.dcbate.tradingplatform.domain.GameSession;
import com.dcbate.tradingplatform.domain.GameStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameSessionRepository extends JpaRepository<GameSession, UUID> {

    Optional<GameSession> findFirstByClientIdAndStatusOrderByStartedAtDesc(String clientId, GameStatus status);

    List<GameSession> findByClientIdAndStatusNot(String clientId, GameStatus status);

    /** Only finished sessions have a {@code finalNetWorth} — the null-check excludes anything still IN_PROGRESS. */
    List<GameSession> findTop10ByDifficultyAndFinalNetWorthIsNotNullOrderByFinalNetWorthDesc(GameDifficulty difficulty);

    /**
     * All WON sessions for a difficulty, unsorted — the fastest-win leaderboard needs to rank by
     * {@code finishedAt - startedAt}, which isn't something a derived query name (or a portable
     * JPQL expression) can order by directly, so {@code GameServiceImpl.getLeaderboard} sorts and
     * takes the top 10 in Java instead, the same "fetch, then compute" pattern {@code getStats}
     * already uses for its own aggregates.
     */
    List<GameSession> findByDifficultyAndStatus(GameDifficulty difficulty, GameStatus status);
}
