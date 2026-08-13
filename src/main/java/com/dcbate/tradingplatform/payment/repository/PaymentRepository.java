package com.dcbate.tradingplatform.payment.repository;

import com.dcbate.tradingplatform.domain.Payment;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findTopByClientIdAndPaymentIdNotOrderByCreatedAtDesc(String clientId, UUID paymentId);

    @Query("SELECT COALESCE(AVG(p.amount), 0) FROM Payment p WHERE p.clientId = :clientId AND p.status = 'SETTLED'")
    BigDecimal averageSettledAmountByClientId(@Param("clientId") String clientId);
}
