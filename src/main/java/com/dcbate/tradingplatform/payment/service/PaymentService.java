package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.payment.api.dto.PaymentRequest;
import com.dcbate.tradingplatform.payment.api.dto.PaymentResponse;
import java.util.UUID;

public interface PaymentService {

    /** Idempotent on {@code idempotencyKey}: a resubmitted key returns the existing payment. */
    PaymentResponse submitPayment(PaymentRequest request);

    PaymentResponse getPayment(UUID paymentId);
}
