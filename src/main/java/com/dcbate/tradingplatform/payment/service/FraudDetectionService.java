package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.kafka.event.PaymentEvent;

public interface FraudDetectionService {

    /** Runs the fraud rules and routes the payment accordingly (block/review vs. validated). */
    void evaluate(PaymentEvent event);
}
