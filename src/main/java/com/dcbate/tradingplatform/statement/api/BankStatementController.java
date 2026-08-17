package com.dcbate.tradingplatform.statement.api;

import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.statement.api.dto.BankStatementResponse;
import com.dcbate.tradingplatform.statement.service.BankStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** One unified, chronological view of everything that's happened to a client's money — see {@code BankStatementServiceImpl}. */
@RestController
@RequestMapping("/v1/statement")
@RequiredArgsConstructor
@Tag(name = "Bank Statement", description = "Unified history of orders, payments, transfers, deposits/withdrawals, conversions, and loans")
public class BankStatementController {

    private final BankStatementService bankStatementService;

    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN', 'AUDITOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "Get a client's bank statement — every order, payment, transfer, deposit/withdrawal, conversion, and loan event, newest first",
            description = "Omit accountId for every account; pass it to scope the feed to just one account.")
    public ResponseEntity<BankStatementResponse> getStatement(
            @RequestParam String clientId,
            @RequestParam(required = false) UUID accountId,
            Authentication authentication) {
        return ResponseEntity.ok(bankStatementService.getStatement(clientId, accountId, CallerPrincipal.from(authentication)));
    }
}
