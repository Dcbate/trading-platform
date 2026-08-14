package com.dcbate.tradingplatform.trading.repository;

import com.dcbate.tradingplatform.domain.RiskAlert;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** CRUD only — {@code RiskServiceImpl} writes one row per rejected order, nothing reads them back yet. */
public interface RiskAlertRepository extends JpaRepository<RiskAlert, UUID> {
}
