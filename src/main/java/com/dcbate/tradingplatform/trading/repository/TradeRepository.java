package com.dcbate.tradingplatform.trading.repository;

import com.dcbate.tradingplatform.domain.Trade;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<Trade, UUID> {
}
