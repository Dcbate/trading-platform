package com.dcbate.tradingplatform.loan.service;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.activity.event.ActivityEventListener;
import com.dcbate.tradingplatform.activity.event.ActivityRecordedEvent;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.ActivityType;
import com.dcbate.tradingplatform.domain.Loan;
import com.dcbate.tradingplatform.domain.LoanEventType;
import com.dcbate.tradingplatform.domain.LoanStatus;
import com.dcbate.tradingplatform.exception.AccountNotActiveException;
import com.dcbate.tradingplatform.exception.AccountNotFoundException;
import com.dcbate.tradingplatform.exception.InsufficientFundsException;
import com.dcbate.tradingplatform.exception.LoanNotActiveException;
import com.dcbate.tradingplatform.exception.LoanNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.LoanEvent;
import com.dcbate.tradingplatform.loan.api.dto.LoanRequest;
import com.dcbate.tradingplatform.loan.api.dto.LoanResponse;
import com.dcbate.tradingplatform.loan.repository.LoanRepository;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @see LoanService
 *
 * A repayment is applied to {@code accruedInterest} first, then {@code outstandingPrincipal} —
 * real amortization order. If the requested amount exceeds what's actually owed, only what's
 * owed is taken from the account (never more than the debt), rather than silently overcharging.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanServiceImpl implements LoanService {

    private static final int DAYS_PER_YEAR = 365;

    private final LoanRepository loanRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AccountRepository accountRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public LoanResponse originate(LoanRequest request, CallerPrincipal caller) {
        caller.requireOwner(request.clientId());
        Account account = requireActiveAccount(request.accountId(), request.clientId());

        account.setBalance(account.getBalance().add(request.principal()));
        accountRepository.save(account);

        Instant now = Instant.now();
        Loan loan = loanRepository.save(Loan.builder()
                .loanId(UUID.randomUUID())
                .clientId(request.clientId())
                .accountId(request.accountId())
                .principal(request.principal())
                .outstandingPrincipal(request.principal())
                .interestRateAnnualPercent(request.productType().getInterestRateAnnualPercent())
                .productType(request.productType())
                .termMonths(request.productType().getTermMonths())
                .accruedInterest(BigDecimal.ZERO)
                .status(LoanStatus.ACTIVE)
                .createdAt(now)
                .lastAccrualAt(now)
                .build());

        publishKafkaEvent(loan, LoanEventType.ORIGINATED, request.principal());
        recordActivity(loan, account, ActivityType.LOAN_ORIGINATED, request.principal(), "Loan originated — " + loan.getProductType());
        log.info("Loan originated: loanId={}, clientId={}, accountId={}, productType={}, principal={}, rate={}, termMonths={}",
                loan.getLoanId(), loan.getClientId(), loan.getAccountId(), loan.getProductType(),
                loan.getPrincipal(), loan.getInterestRateAnnualPercent(), loan.getTermMonths());

        return LoanResponse.from(loan);
    }

    @Override
    @Transactional(readOnly = true)
    public LoanResponse getLoan(UUID loanId, CallerPrincipal caller) {
        Loan loan = requireLoan(loanId);
        caller.requireOwner(loan.getClientId());
        return LoanResponse.from(loan);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanResponse> listLoansForClient(String clientId, CallerPrincipal caller) {
        caller.requireOwner(clientId);
        return loanRepository.findByClientId(clientId).stream().map(LoanResponse::from).toList();
    }

    @Override
    @Transactional
    public LoanResponse repay(UUID loanId, BigDecimal amount, CallerPrincipal caller) {
        Loan loan = requireLoan(loanId);
        caller.requireOwner(loan.getClientId());
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            throw new LoanNotActiveException(loanId);
        }

        BigDecimal totalOwed = loan.getAccruedInterest().add(loan.getOutstandingPrincipal());
        BigDecimal payment = amount.min(totalOwed);

        Account account = requireActiveAccount(loan.getAccountId(), loan.getClientId());
        if (account.getBalance().compareTo(payment) < 0) {
            throw new InsufficientFundsException(account.getAccountId(), payment, account.getBalance());
        }
        account.setBalance(account.getBalance().subtract(payment));
        accountRepository.save(account);

        BigDecimal remaining = payment;
        BigDecimal interestPaid = remaining.min(loan.getAccruedInterest());
        loan.setAccruedInterest(loan.getAccruedInterest().subtract(interestPaid));
        remaining = remaining.subtract(interestPaid);

        BigDecimal principalPaid = remaining.min(loan.getOutstandingPrincipal());
        loan.setOutstandingPrincipal(loan.getOutstandingPrincipal().subtract(principalPaid));

        if (loan.getOutstandingPrincipal().signum() == 0 && loan.getAccruedInterest().signum() == 0) {
            loan.setStatus(LoanStatus.PAID_OFF);
        }
        Loan saved = loanRepository.save(loan);

        publishKafkaEvent(saved, LoanEventType.REPAYMENT, payment);
        recordActivity(saved, account, ActivityType.LOAN_REPAYMENT, payment.negate(),
                "Loan repayment — %s outstanding".formatted(saved.getOutstandingPrincipal()));
        log.info("Loan repayment: loanId={}, payment={}, outstandingPrincipal={}, accruedInterest={}, status={}",
                loanId, payment, saved.getOutstandingPrincipal(), saved.getAccruedInterest(), saved.getStatus());

        return LoanResponse.from(saved);
    }

    @Override
    @Transactional
    public void accrueInterest() {
        Instant now = Instant.now();
        List<Loan> activeLoans = loanRepository.findByStatus(LoanStatus.ACTIVE);
        BigDecimal totalAccrued = BigDecimal.ZERO;
        for (Loan loan : activeLoans) {
            long days = Duration.between(loan.getLastAccrualAt(), now).toDays();
            if (days <= 0) {
                continue;
            }
            BigDecimal accrual = calculateAccrual(loan.getOutstandingPrincipal(), loan.getInterestRateAnnualPercent(), days);
            loan.setAccruedInterest(loan.getAccruedInterest().add(accrual));
            loan.setLastAccrualAt(now);
            loanRepository.save(loan);
            totalAccrued = totalAccrued.add(accrual);
        }
        meterRegistry.counter("loan.interest.accrued").increment(totalAccrued.doubleValue());
        log.info("Interest accrual run complete: loansProcessed={}, totalAccrued={}", activeLoans.size(), totalAccrued);
    }

    /** Simple (non-compounding) daily interest: principal * (annualRate/100) * (days/365). */
    BigDecimal calculateAccrual(BigDecimal outstandingPrincipal, BigDecimal annualRatePercent, long days) {
        return outstandingPrincipal
                .multiply(annualRatePercent)
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(100L * DAYS_PER_YEAR), 8, RoundingMode.HALF_UP);
    }

    private Account requireActiveAccount(UUID accountId, String expectedClientId) {
        Account account = accountRepository.findById(accountId)
                .filter(a -> a.getClientId().equals(expectedClientId))
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(accountId);
        }
        return account;
    }

    private Loan requireLoan(UUID loanId) {
        return loanRepository.findById(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
    }

    private void publishKafkaEvent(Loan loan, LoanEventType type, BigDecimal amount) {
        kafkaEventPublisher.publish(topics.loans(), loan.getClientId(), new LoanEvent(
                loan.getLoanId(), loan.getClientId(), type, amount,
                loan.getOutstandingPrincipal(), loan.getAccruedInterest(), loan.getStatus(), Instant.now()));
    }

    /**
     * Publishes the one immutable activity record this event produces — {@link
     * ActivityEventListener} turns it into a persisted row once this method's transaction
     * actually commits; see its javadoc for why.
     */
    private void recordActivity(Loan loan, Account account, ActivityType type, BigDecimal signedAmount, String description) {
        eventPublisher.publishEvent(new ActivityRecordedEvent(
                loan.getClientId(), account.getAccountId(), type, signedAmount, account.getCurrency(), description));
    }
}
