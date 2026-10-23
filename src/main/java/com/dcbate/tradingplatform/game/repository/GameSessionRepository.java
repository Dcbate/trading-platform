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

    /**
     * The "best net worth" leaderboard's source query — WON is excluded on purpose. A win ends the
     * session the instant net worth reaches the goal, so a winner's final net worth is just an
     * accident of which trade happened to cross the line, not a skill signal; that's what the
     * "fastest win" leaderboard measures properly instead. This board is for sessions that didn't
     * win: how close did you get before time ran out or you went bankrupt. The null-check on
     * {@code finalNetWorth} excludes anything still IN_PROGRESS.
     */
    List<GameSession> findTop10ByDifficultyAndStatusNotAndFinalNetWorthIsNotNullOrderByFinalNetWorthDesc(GameDifficulty difficulty, GameStatus excludedStatus);

    /**
     * All WON sessions for a difficulty, unsorted — the fastest-win leaderboard needs to rank by
     * {@code finishedAt - startedAt}, which isn't something a derived query name (or a portable
     * JPQL expression) can order by directly, so {@code GameServiceImpl.getLeaderboard} sorts and
     * takes the top 10 in Java instead, the same "fetch, then compute" pattern {@code getStats}
     * already uses for its own aggregates.
     */
    List<GameSession> findByDifficultyAndStatus(GameDifficulty difficulty, GameStatus status);
}
