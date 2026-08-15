package com.dcbate.tradingplatform.game.repository;

import com.dcbate.tradingplatform.domain.GameTrade;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameTradeRepository extends JpaRepository<GameTrade, UUID> {

    List<GameTrade> findBySessionIdOrderByCreatedAtDesc(UUID sessionId);

    @Query("SELECT t FROM GameTrade t WHERE t.sessionId IN :sessionIds AND t.realizedPnl IS NOT NULL ORDER BY t.realizedPnl DESC")
    List<GameTrade> findBestRealizedTrades(@Param("sessionIds") List<UUID> sessionIds);
}
