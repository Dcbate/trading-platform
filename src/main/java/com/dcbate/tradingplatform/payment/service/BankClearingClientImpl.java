package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.config.SettlementProperties;
import com.dcbate.tradingplatform.domain.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a real bank clearing gateway — none exists for Phase 2. Deterministically fails
 * above a configurable amount so the saga's compensation path is actually exercisable and tested,
 * not just theoretical. This threshold is a test seam, not a business rule.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankClearingClientImpl implements BankClearingClient {

    private final SettlementProperties settlementProperties;

    @Override
    public boolean clear(Payment payment) {
        boolean succeeds = payment.getAmount().compareTo(settlementProperties.simulatedBankFailureThreshold()) <= 0;
        log.info(
                "Simulated bank clearing for paymentId={}: amount={}, result={}",
                payment.getPaymentId(), payment.getAmount(), succeeds ? "CLEARED" : "FAILED");
        return succeeds;
    }
}
