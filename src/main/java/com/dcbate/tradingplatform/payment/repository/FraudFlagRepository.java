package com.dcbate.tradingplatform.payment.repository;

import com.dcbate.tradingplatform.domain.FraudFlag;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** CRUD only — {@code FraudDetectionServiceImpl} is the sole writer. */
public interface FraudFlagRepository extends JpaRepository<FraudFlag, UUID> {
}
