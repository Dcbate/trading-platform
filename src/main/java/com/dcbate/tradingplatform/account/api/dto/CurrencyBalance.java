package com.dcbate.tradingplatform.account.api.dto;

import java.math.BigDecimal;

/** One currency's slice of a {@link BalanceSummaryResponse} — money in different currencies can't be summed. */
public record CurrencyBalance(String currency, BigDecimal totalBalance, int accountCount) {}
