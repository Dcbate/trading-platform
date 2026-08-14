package com.dcbate.tradingplatform.kafka.event;

import com.dcbate.tradingplatform.domain.LoanEventType;
import com.dcbate.tradingplatform.domain.LoanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published to {@code loans} on origination and on each repayment. */
public record LoanEvent(
        UUID loanId, String clientId, LoanEventType type, BigDecimal amount,
        BigDecimal outstandingPrincipal, BigDecimal accruedInterest, LoanStatus status, Instant occurredAt) {
}
