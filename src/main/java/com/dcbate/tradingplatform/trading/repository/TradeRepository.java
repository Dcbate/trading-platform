package com.dcbate.tradingplatform.trading.repository;

import com.dcbate.tradingplatform.domain.Trade;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** CRUD only — {@code ExecutionServiceImpl} is the sole writer. */
public interface TradeRepository extends JpaRepository<Trade, UUID> {
}
