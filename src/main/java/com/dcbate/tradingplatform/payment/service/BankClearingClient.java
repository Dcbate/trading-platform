package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.domain.Payment;

/** Step 3 of the settlement saga: clear the reserved funds with an external bank. */
public interface BankClearingClient {

    boolean clear(Payment payment);
}
