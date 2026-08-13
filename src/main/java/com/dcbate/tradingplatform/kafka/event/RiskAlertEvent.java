package com.dcbate.tradingplatform.kafka.event;

import com.dcbate.tradingplatform.domain.RiskLevel;
import java.time.Instant;
import java.util.UUID;

/** Published to {@code risk-alerts} whenever the Risk Service rejects an order. */
public record RiskAlertEvent(
        UUID orderId, String clientId, RiskLevel riskLevel, String reason, Instant createdAt) {
}
