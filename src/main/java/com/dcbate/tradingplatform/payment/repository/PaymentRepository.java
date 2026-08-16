package com.dcbate.tradingplatform.payment.repository;

import com.dcbate.tradingplatform.domain.Payment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code findByIdempotencyKey} backs payment idempotency;
 * {@code findTopByClientIdAndPaymentIdNotOrderByCreatedAtDesc} backs the fraud country-change
 * check (the most recent *other* payment for the client); {@code averageSettledAmountByClientId}
 * backs the fraud amount-anomaly check; {@code findByClientIdOrderByCreatedAtDesc} backs the bank
 * statement.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findTopByClientIdAndPaymentIdNotOrderByCreatedAtDesc(String clientId, UUID paymentId);

    List<Payment> findByClientIdOrderByCreatedAtDesc(String clientId);

    @Query("SELECT COALESCE(AVG(p.amount), 0) FROM Payment p WHERE p.clientId = :clientId AND p.status = 'SETTLED'")
    BigDecimal averageSettledAmountByClientId(@Param("clientId") String clientId);
}
