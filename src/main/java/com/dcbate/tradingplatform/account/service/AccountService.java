package com.dcbate.tradingplatform.account.service;

import com.dcbate.tradingplatform.account.api.dto.AccountRequest;
import com.dcbate.tradingplatform.account.api.dto.AccountResponse;
import com.dcbate.tradingplatform.account.api.dto.CloseAccountRequest;
import com.dcbate.tradingplatform.account.api.dto.ConvertRequest;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Account opening, lookup, deposit/withdraw, and FX conversion between a client's own accounts.
 * {@code LedgerServiceImpl}/{@code TransferServiceImpl}/{@code LoanServiceImpl} are the other
 * things that change a balance after opening (payments, internal transfers, loan disbursement).
 * Every method takes the caller's {@link CallerPrincipal} and enforces that a non-staff caller
 * can only act on their own {@code clientId}.
 */
public interface AccountService {

    AccountResponse openAccount(AccountRequest request, CallerPrincipal caller);

    AccountResponse getAccount(UUID accountId, CallerPrincipal caller);

    List<AccountResponse> listAccountsForClient(String clientId, CallerPrincipal caller);

    AccountResponse deposit(UUID accountId, BigDecimal amount, CallerPrincipal caller);

    AccountResponse withdraw(UUID accountId, BigDecimal amount, CallerPrincipal caller);

    AccountResponse convert(UUID fromAccountId, ConvertRequest request, CallerPrincipal caller);

    /**
     * Closes an account. A zero-balance account just closes. A positive balance requires
     * {@code request.destinationAccountId()} — swept there in full before the account flips to
     * {@code CLOSED} — since money can't simply disappear; there's no cash withdrawal that erases
     * a balance without moving it somewhere.
     */
    AccountResponse closeAccount(UUID accountId, CloseAccountRequest request, CallerPrincipal caller);
}
