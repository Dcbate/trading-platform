package com.dcbate.tradingplatform.kafka.event;

import com.dcbate.tradingplatform.domain.FraudAction;
import com.dcbate.tradingplatform.domain.RiskLevel;
import java.time.Instant;
import java.util.UUID;

/** Published to {@code fraud-alerts} whenever the Fraud Detection Service flags a payment. */
public record FraudAlertEvent(
        UUID paymentId,
        String clientId,
        RiskLevel riskLevel,
        FraudAction actionTaken,
        String reason,
        Instant createdAt) {
}
