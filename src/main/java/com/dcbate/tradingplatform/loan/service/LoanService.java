package com.dcbate.tradingplatform.loan.service;

import com.dcbate.tradingplatform.loan.api.dto.LoanRequest;
import com.dcbate.tradingplatform.loan.api.dto.LoanResponse;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Loan origination, lookup, and repayment. {@code accrueInterest()} is driven by {@code LoanInterestScheduler}, not called from the API. */
public interface LoanService {

    LoanResponse originate(LoanRequest request, CallerPrincipal caller);

    LoanResponse getLoan(UUID loanId, CallerPrincipal caller);

    List<LoanResponse> listLoansForClient(String clientId, CallerPrincipal caller);

    LoanResponse repay(UUID loanId, BigDecimal amount, CallerPrincipal caller);

    void accrueInterest();
}
