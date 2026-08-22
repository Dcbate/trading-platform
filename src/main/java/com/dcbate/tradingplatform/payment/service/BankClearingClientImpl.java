package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.config.SettlementProperties;
import com.dcbate.tradingplatform.domain.Payment;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a real bank clearing gateway — none exists for Phase 2. Deterministically fails
 * above a configurable amount so the saga's compensation path is actually exercisable and tested,
 * not just theoretical. This threshold is a test seam, not a business rule.
 *
 * <p>Wrapped in a circuit breaker ({@code config/ResilienceConfig.java}) even though this
 * simulation never actually throws today — the exact shape a real gateway (with real timeouts and
 * connection failures) would need is already here and tested, not something to retrofit later.
 * When the breaker is open, {@link #clear} fails closed (returns {@code false}, the same as a
 * genuine decline) rather than letting {@link CallNotPermittedException} escape — the settlement
 * saga only knows how to react to "cleared" or "declined," and assuming success when we couldn't
 * even reach the gateway would be the one genuinely unsafe choice here.
 */
@Slf4j
@Component
public class BankClearingClientImpl implements BankClearingClient {

    private final SettlementProperties settlementProperties;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public BankClearingClientImpl(SettlementProperties settlementProperties, CircuitBreaker bankClearingCircuitBreaker) {
        this.settlementProperties = settlementProperties;
        this.circuitBreaker = bankClearingCircuitBreaker;
    }

    /** Package-private seam for tests that don't care about the breaker. */
    BankClearingClientImpl(SettlementProperties settlementProperties) {
        this(settlementProperties, CircuitBreaker.ofDefaults("test-bank-clearing"));
    }

    @Override
    public boolean clear(Payment payment) {
        try {
            return circuitBreaker.executeSupplier(() -> doClear(payment));
        } catch (CallNotPermittedException e) {
            log.warn(
                    "Bank clearing circuit breaker is open — treating paymentId={} as declined rather than assume it cleared",
                    payment.getPaymentId());
            return false;
        }
    }

    private boolean doClear(Payment payment) {
        boolean succeeds = payment.getAmount().compareTo(settlementProperties.simulatedBankFailureThreshold()) <= 0;
        log.info(
                "Simulated bank clearing for paymentId={}: amount={}, result={}",
                payment.getPaymentId(), payment.getAmount(), succeeds ? "CLEARED" : "FAILED");
        return succeeds;
    }
}
