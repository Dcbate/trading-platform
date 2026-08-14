package com.dcbate.tradingplatform.domain;

/** What kind of balance-changing activity happened directly on an account (not via a payment or transfer). */
public enum AccountActivityType {
    DEPOSIT,
    WITHDRAWAL,
    CONVERSION,
    CLOSURE
}
