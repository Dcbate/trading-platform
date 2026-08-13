package com.dcbate.tradingplatform.trading.repository;

import com.dcbate.tradingplatform.domain.RiskAlert;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskAlertRepository extends JpaRepository<RiskAlert, UUID> {
}
