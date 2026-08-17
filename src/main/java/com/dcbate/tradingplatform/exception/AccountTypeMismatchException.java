package com.dcbate.tradingplatform.exception;

import com.dcbate.tradingplatform.domain.AccountType;
import java.util.UUID;

/** Thrown when an account funding a trade isn't the type that instrument requires (e.g. a crypto trade against a non-{@code CRYPTO} account); mapped to 409. */
public class AccountTypeMismatchException extends RuntimeException {

    public AccountTypeMismatchException(UUID accountId, AccountType expected, AccountType actual) {
        super("Account %s is %s, but this trade requires a %s account".formatted(accountId, actual, expected));
    }
}
