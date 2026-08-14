package com.dcbate.tradingplatform.payment.repository;

import com.dcbate.tradingplatform.domain.ReconciliationAlert;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code existsByPaymentId} guards against re-alerting on a settlement already flagged in a previous run. */
public interface ReconciliationAlertRepository extends JpaRepository<ReconciliationAlert, UUID> {

    boolean existsByPaymentId(UUID paymentId);
}
