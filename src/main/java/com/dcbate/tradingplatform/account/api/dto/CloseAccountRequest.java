package com.dcbate.tradingplatform.account.api.dto;

import java.util.UUID;

/**
 * Body for {@code POST /v1/accounts/{accountId}/close}. {@code destinationAccountId} is only
 * required if the account being closed has a positive balance — a zero-balance account closes
 * with no body at all. The destination doesn't have to belong to the caller: closing an account
 * with money in it can sweep the balance into another of the caller's own accounts, or into
 * someone else's account entirely, the same way a transfer's recipient can be anyone.
 */
public record CloseAccountRequest(UUID destinationAccountId) {

    public static final CloseAccountRequest EMPTY = new CloseAccountRequest(null);
}
