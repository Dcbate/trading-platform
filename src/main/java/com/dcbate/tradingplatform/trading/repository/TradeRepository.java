package com.dcbate.tradingplatform.trading.repository;

import com.dcbate.tradingplatform.domain.Trade;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code ExecutionServiceImpl} is the sole writer; {@code sumQuantityByOrderId} backs the
 *  Matching Engine's startup recovery (computing how much of a {@code PARTIALLY_FILLED} order is
 *  still outstanding, since {@code Order} itself doesn't track a running filled quantity). */
public interface TradeRepository extends JpaRepository<Trade, UUID> {

    @Query("SELECT COALESCE(SUM(t.quantity), 0) FROM Trade t WHERE t.buyOrderId = :orderId OR t.sellOrderId = :orderId")
    BigDecimal sumQuantityByOrderId(@Param("orderId") UUID orderId);
}
