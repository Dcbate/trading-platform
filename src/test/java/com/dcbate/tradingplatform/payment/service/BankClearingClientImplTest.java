package com.dcbate.tradingplatform.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcbate.tradingplatform.config.SettlementProperties;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
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
}
