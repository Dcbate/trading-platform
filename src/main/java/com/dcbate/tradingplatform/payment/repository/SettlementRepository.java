package com.dcbate.tradingplatform.payment.repository;

import com.dcbate.tradingplatform.domain.Settlement;
import com.dcbate.tradingplatform.domain.SettlementStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    Optional<Settlement> findByPaymentId(UUID paymentId);

    List<Settlement> findByStatus(SettlementStatus status);
}
