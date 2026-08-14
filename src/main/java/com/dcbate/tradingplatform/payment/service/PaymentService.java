package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.payment.api.dto.PaymentRequest;
import com.dcbate.tradingplatform.payment.api.dto.PaymentResponse;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import java.util.UUID;

/**
 * Payment intake and lookup — the "pay other banks" entry point of the payment pipeline (see
 * {@code docs/PAYMENT_SYSTEM.md}); contrast with {@code TransferService} for "pay other users at
 * this bank." Every method takes the caller's {@link CallerPrincipal} and enforces that a
 * non-staff caller can only submit/view their own payments.
 */
public interface PaymentService {

    /** Idempotent on {@code idempotencyKey}: a resubmitted key returns the existing payment. */
    PaymentResponse submitPayment(PaymentRequest request, CallerPrincipal caller);

    PaymentResponse getPayment(UUID paymentId, CallerPrincipal caller);
}
