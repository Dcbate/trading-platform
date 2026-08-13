package com.dcbate.tradingplatform.payment.repository;

import com.dcbate.tradingplatform.domain.FraudFlag;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudFlagRepository extends JpaRepository<FraudFlag, UUID> {
}
