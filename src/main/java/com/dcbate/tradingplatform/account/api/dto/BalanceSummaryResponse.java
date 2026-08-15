package com.dcbate.tradingplatform.account.api.dto;

import java.util.List;

/**
 * A client's total balance across their {@code ACTIVE} accounts, grouped by currency. Closed and
 * frozen accounts are excluded — a closed account's balance is always zero (see
 * {@code AccountServiceImpl.closeAccount}) and a frozen one isn't spendable, so counting either
 * toward "how much money do I have" would be misleading.
 */
public record BalanceSummaryResponse(String clientId, int activeAccountCount, List<CurrencyBalance> balances) {}
