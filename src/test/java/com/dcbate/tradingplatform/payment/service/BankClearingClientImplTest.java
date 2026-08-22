package com.dcbate.tradingplatform.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcbate.tradingplatform.config.SettlementProperties;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BankClearingClientImplTest {

    private final BankClearingClientImpl client =
            new BankClearingClientImpl(new SettlementProperties(new BigDecimal("500000")));

    private Payment payment(String amount) {
        return Payment.builder()
                .paymentId(UUID.randomUUID()).clientId("client-1").amount(new BigDecimal(amount))
                .status(PaymentStatus.RESERVED).idempotencyKey("key").country("US").createdAt(Instant.now())
                .build();
    }

    @Test
    void amountAtOrBelowThresholdClears() {
        assertThat(client.clear(payment("500000.00"))).isTrue();
        assertThat(client.clear(payment("1.00"))).isTrue();
    }

    @Test
    void amountAboveThresholdFails() {
        assertThat(client.clear(payment("500000.01"))).isFalse();
    }

    @Test
    void failsClosedRatherThanAssumeSuccessWhenTheCircuitBreakerIsOpen() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("forced-open-bank");
        breaker.transitionToOpenState();
        BankClearingClientImpl clientWithOpenBreaker =
                new BankClearingClientImpl(new SettlementProperties(new BigDecimal("500000")), breaker);

        // Well within the normal-clearing threshold — would clear if the breaker let the call
        // through at all. An open breaker must never be silently treated as a success.
        assertThat(clientWithOpenBreaker.clear(payment("1.00"))).isFalse();
    }

    @Test
    void repeatedBusinessDeclinesNeverTripTheBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50.0f)
                .build();
        CircuitBreaker breaker = CircuitBreaker.of("test-declines", config);
        BankClearingClientImpl clientWithBreaker =
                new BankClearingClientImpl(new SettlementProperties(new BigDecimal("500000")), breaker);

        // A "declined, amount over threshold" result is a normal method return, not a thrown
        // exception — the breaker must stay closed no matter how many declines happen in a row.
        for (int i = 0; i < 10; i++) {
            assertThat(clientWithBreaker.clear(payment("500000.01"))).isFalse();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
