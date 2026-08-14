package com.dcbate.tradingplatform.domain;

/** What the Fraud Detection Service decided: let it through, hold it for compliance review, or block it outright. */
public enum FraudAction {
    PASS,
    REVIEW,
    BLOCK
}
