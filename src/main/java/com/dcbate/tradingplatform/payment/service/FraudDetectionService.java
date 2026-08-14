package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.kafka.event.PaymentEvent;
import java.util.UUID;

/** Runs the three fraud rules (velocity, country-change, amount-anomaly) and owns the {@code UNDER_REVIEW}/{@code BLOCKED} statuses, including their compliance-officer resolution. */
public interface FraudDetectionService {

    /** Runs the fraud rules and routes the payment accordingly (block/review vs. validated). */
    void evaluate(PaymentEvent event);

    /** Compliance-officer override: releases an {@code UNDER_REVIEW} payment into settlement. */
    void approve(UUID paymentId);

    /** Compliance-officer override: terminally blocks an {@code UNDER_REVIEW} payment. */
    void reject(UUID paymentId);
}
