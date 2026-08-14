package com.dcbate.tradingplatform.domain;

/** What kind of loan lifecycle event {@code LoanEvent} carries — published to the {@code loans} Kafka topic. */
public enum LoanEventType {
    ORIGINATED,
    REPAYMENT
}
