package com.dcbate.tradingplatform.trading.repository;

import com.dcbate.tradingplatform.domain.Position;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionRepository extends JpaRepository<Position, UUID> {

    Optional<Position> findByAccountIdAndSymbol(UUID accountId, String symbol);

    List<Position> findByClientId(String clientId);
}
