package com.dcbate.tradingplatform.payment.repository;

import com.dcbate.tradingplatform.domain.ReconciliationAlert;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationAlertRepository extends JpaRepository<ReconciliationAlert, UUID> {

    boolean existsByPaymentId(UUID paymentId);
}
