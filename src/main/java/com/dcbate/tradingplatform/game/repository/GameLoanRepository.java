package com.dcbate.tradingplatform.game.repository;

import com.dcbate.tradingplatform.domain.GameLoan;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameLoanRepository extends JpaRepository<GameLoan, UUID> {

    List<GameLoan> findBySessionId(UUID sessionId);
}
