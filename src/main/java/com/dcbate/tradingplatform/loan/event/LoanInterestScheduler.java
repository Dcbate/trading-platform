package com.dcbate.tradingplatform.loan.event;

import com.dcbate.tradingplatform.loan.service.LoanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cron-driven trigger for {@code LoanServiceImpl.accrueInterest()}; schedule is {@code loan.interest.accrual-cron}. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoanInterestScheduler {

    private final LoanService loanService;

    @Scheduled(cron = "${loan.interest.accrual-cron}")
    public void run() {
        try {
            loanService.accrueInterest();
        } catch (Exception e) {
            log.error("Loan interest accrual run failed: {}", e.getMessage(), e);
        }
    }
}
