package com.dcbate.tradingplatform.loan.api;

import com.dcbate.tradingplatform.domain.LoanProductType;
import com.dcbate.tradingplatform.loan.api.dto.LoanProductResponse;
import com.dcbate.tradingplatform.loan.api.dto.LoanRequest;
import com.dcbate.tradingplatform.loan.api.dto.LoanResponse;
import com.dcbate.tradingplatform.loan.service.LoanService;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
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

/** Loan origination, lookup, and repayment. Interest accrual is scheduled — see {@code LoanInterestScheduler}. */
@Slf4j
@RestController
@RequestMapping("/v1/loans")
@RequiredArgsConstructor
@Tag(name = "Loans", description = "Loan origination, lookup, and repayment")
public class LoanController {

    private final LoanService loanService;

    @GetMapping("/products")
    @Operation(summary = "List the bank's fixed loan product catalog (rate and term per product)")
    public ResponseEntity<List<LoanProductResponse>> listProducts() {
        return ResponseEntity.ok(Arrays.stream(LoanProductType.values()).map(LoanProductResponse::from).toList());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Originate a loan, disbursing the principal into the given account")
    public ResponseEntity<LoanResponse> originate(@Valid @RequestBody LoanRequest request, Authentication authentication) {
        LoanResponse response = loanService.originate(request, CallerPrincipal.from(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{loanId}")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN', 'AUDITOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "Look up a loan's principal, outstanding balance, and accrued interest")
    public ResponseEntity<LoanResponse> getLoan(@PathVariable UUID loanId, Authentication authentication) {
        return ResponseEntity.ok(loanService.getLoan(loanId, CallerPrincipal.from(authentication)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN', 'AUDITOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "List a client's loans")
    public ResponseEntity<List<LoanResponse>> listLoans(@RequestParam String clientId, Authentication authentication) {
        return ResponseEntity.ok(loanService.listLoansForClient(clientId, CallerPrincipal.from(authentication)));
    }

    @PostMapping("/{loanId}/repay")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Make a repayment, applied to accrued interest first then outstanding principal")
    public ResponseEntity<LoanResponse> repay(
            @PathVariable UUID loanId, @Valid @RequestBody RepaymentRequest request, Authentication authentication) {
        return ResponseEntity.ok(loanService.repay(loanId, request.amount(), CallerPrincipal.from(authentication)));
    }

    /** Body for {@code POST /v1/loans/{loanId}/repay}. */
    public record RepaymentRequest(
            @NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount) {
    }
}
