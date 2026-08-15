package com.dcbate.tradingplatform.account.api;

import com.dcbate.tradingplatform.account.api.dto.AccountRequest;
import com.dcbate.tradingplatform.account.api.dto.AccountResponse;
import com.dcbate.tradingplatform.account.api.dto.AccountTransactionRequest;
import com.dcbate.tradingplatform.account.api.dto.BalanceSummaryResponse;
import com.dcbate.tradingplatform.account.api.dto.CloseAccountRequest;
import com.dcbate.tradingplatform.account.api.dto.ConvertRequest;
import com.dcbate.tradingplatform.account.service.AccountService;
import com.dcbate.tradingplatform.config.TradingProperties;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account opening, lookup, deposit/withdraw, and FX conversion — where a client's money actually
 * lives before it funds a payment, an internal transfer, or an FX order. Every method resolves a
 * {@link CallerPrincipal} from the caller's JWT and passes it to the service layer, which enforces
 * that a non-staff caller can only act on their own accounts.
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Account opening, lookup, deposit/withdraw, and FX conversion")
public class AccountController {

    private final AccountService accountService;
    private final TradingProperties tradingProperties;

    @GetMapping("/currencies")
    @Operation(summary = "List the currencies the bank offers accounts in (derived from the FX desk's traded currency pairs)")
    public ResponseEntity<List<String>> listCurrencies() {
        TreeSet<String> currencies = new TreeSet<>();
        tradingProperties.currencyPairs().forEach(pair -> currencies.addAll(Arrays.asList(pair.split("/"))));
        return ResponseEntity.ok(List.copyOf(currencies));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Open a new account for a client")
    public ResponseEntity<AccountResponse> openAccount(@Valid @RequestBody AccountRequest request, Authentication authentication) {
        AccountResponse response = accountService.openAccount(request, CallerPrincipal.from(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{accountId}")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN', 'AUDITOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "Look up an account by id")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID accountId, Authentication authentication) {
        return ResponseEntity.ok(accountService.getAccount(accountId, CallerPrincipal.from(authentication)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN', 'AUDITOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "List a client's accounts")
    public ResponseEntity<List<AccountResponse>> listAccounts(@RequestParam String clientId, Authentication authentication) {
        return ResponseEntity.ok(accountService.listAccountsForClient(clientId, CallerPrincipal.from(authentication)));
    }

    @GetMapping("/balances")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN', 'AUDITOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "Total balance across a client's active accounts, grouped by currency")
    public ResponseEntity<BalanceSummaryResponse> getBalanceSummary(@RequestParam String clientId, Authentication authentication) {
        return ResponseEntity.ok(accountService.getBalanceSummary(clientId, CallerPrincipal.from(authentication)));
    }

    @PostMapping("/{accountId}/deposit")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Deposit money into an account")
    public ResponseEntity<AccountResponse> deposit(
            @PathVariable UUID accountId, @Valid @RequestBody AccountTransactionRequest request, Authentication authentication) {
        return ResponseEntity.ok(accountService.deposit(accountId, request.amount(), CallerPrincipal.from(authentication)));
    }

    @PostMapping("/{accountId}/withdraw")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Withdraw money from an account")
    public ResponseEntity<AccountResponse> withdraw(
            @PathVariable UUID accountId, @Valid @RequestBody AccountTransactionRequest request, Authentication authentication) {
        return ResponseEntity.ok(accountService.withdraw(accountId, request.amount(), CallerPrincipal.from(authentication)));
    }

    @PostMapping("/{accountId}/convert")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Convert (sell) balance from one of the caller's own accounts into another currency")
    public ResponseEntity<AccountResponse> convert(
            @PathVariable UUID accountId, @Valid @RequestBody ConvertRequest request, Authentication authentication) {
        return ResponseEntity.ok(accountService.convert(accountId, request, CallerPrincipal.from(authentication)));
    }

    @PostMapping("/{accountId}/close")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Close an account — a positive balance must be swept to a destination account first")
    public ResponseEntity<AccountResponse> closeAccount(
            @PathVariable UUID accountId,
            @RequestBody(required = false) CloseAccountRequest request,
            Authentication authentication) {
        CloseAccountRequest body = request == null ? CloseAccountRequest.EMPTY : request;
        return ResponseEntity.ok(accountService.closeAccount(accountId, body, CallerPrincipal.from(authentication)));
    }
}
