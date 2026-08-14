package com.dcbate.tradingplatform.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.AccountType;
import com.dcbate.tradingplatform.domain.Loan;
import com.dcbate.tradingplatform.domain.LoanProductType;
import com.dcbate.tradingplatform.domain.LoanStatus;
import com.dcbate.tradingplatform.exception.LoanNotActiveException;
import com.dcbate.tradingplatform.exception.LoanNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.loan.api.dto.LoanRequest;
import com.dcbate.tradingplatform.loan.api.dto.LoanResponse;
import com.dcbate.tradingplatform.loan.repository.LoanRepository;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class LoanServiceImplTest {

    @Mock
    private LoanRepository loanRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    private LoanServiceImpl loanService;

    private final UUID accountId = UUID.randomUUID();
    private final CallerPrincipal owner = new CallerPrincipal("client-1", false);
    private final CallerPrincipal otherClient = new CallerPrincipal("client-2", false);

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "settlements", "fraud-alerts", "notifications", "notifications-dlq",
                "account-activity", "transfers", "loans");
        loanService = new LoanServiceImpl(loanRepository, accountRepository, kafkaEventPublisher, topics, new SimpleMeterRegistry());
    }

    private Account account(BigDecimal balance) {
        return Account.builder()
                .accountId(accountId).clientId("client-1").accountType(AccountType.CHECKING)
                .currency("USD").balance(balance).status(AccountStatus.ACTIVE).createdAt(Instant.now())
                .build();
    }

    private Loan activeLoan(UUID loanId, BigDecimal outstandingPrincipal, BigDecimal accruedInterest) {
        return Loan.builder()
                .loanId(loanId).clientId("client-1").accountId(accountId)
                .principal(new BigDecimal("1000.00")).outstandingPrincipal(outstandingPrincipal)
                .interestRateAnnualPercent(new BigDecimal("5.0")).productType(LoanProductType.PERSONAL_SHORT).termMonths(12)
                .accruedInterest(accruedInterest)
                .status(LoanStatus.ACTIVE).createdAt(Instant.now()).lastAccrualAt(Instant.now())
                .build();
    }

    @Test
    void originateCreditsAccountAndCreatesLoan() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(new BigDecimal("100.00"))));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        LoanResponse response = loanService.originate(
                new LoanRequest("client-1", accountId, new BigDecimal("1000.00"), LoanProductType.PERSONAL_SHORT), owner);

        assertThat(response.status()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(response.outstandingPrincipal()).isEqualByComparingTo("1000.00");
        assertThat(response.accruedInterest()).isEqualByComparingTo("0");
        assertThat(response.productType()).isEqualTo(LoanProductType.PERSONAL_SHORT);
        assertThat(response.termMonths()).isEqualTo(12);
        assertThat(response.interestRateAnnualPercent()).isEqualByComparingTo(LoanProductType.PERSONAL_SHORT.getInterestRateAnnualPercent());
    }

    @Test
    void originateDeniedForAnotherClient() {
        LoanRequest request = new LoanRequest("client-2", accountId, new BigDecimal("1000.00"), LoanProductType.PERSONAL_SHORT);

        assertThatThrownBy(() -> loanService.originate(request, owner)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getLoanThrowsWhenMissing() {
        UUID loanId = UUID.randomUUID();
        when(loanRepository.findById(loanId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loanService.getLoan(loanId, owner)).isInstanceOf(LoanNotFoundException.class);
    }

    @Test
    void getLoanDeniedForNonOwner() {
        UUID loanId = UUID.randomUUID();
        when(loanRepository.findById(loanId)).thenReturn(Optional.of(activeLoan(loanId, new BigDecimal("1000.00"), BigDecimal.ZERO)));

        assertThatThrownBy(() -> loanService.getLoan(loanId, otherClient)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void repayAppliesToInterestBeforePrincipal() {
        UUID loanId = UUID.randomUUID();
        Loan loan = activeLoan(loanId, new BigDecimal("1000.00"), new BigDecimal("30.00"));
        when(loanRepository.findById(loanId)).thenReturn(Optional.of(loan));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(new BigDecimal("500.00"))));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        LoanResponse response = loanService.repay(loanId, new BigDecimal("50.00"), owner);

        assertThat(response.accruedInterest()).isEqualByComparingTo("0");
        assertThat(response.outstandingPrincipal()).isEqualByComparingTo("980.00");
        assertThat(response.status()).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void repayMarksPaidOffWhenFullyRepaid() {
        UUID loanId = UUID.randomUUID();
        Loan loan = activeLoan(loanId, new BigDecimal("100.00"), new BigDecimal("5.00"));
        when(loanRepository.findById(loanId)).thenReturn(Optional.of(loan));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(new BigDecimal("500.00"))));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        LoanResponse response = loanService.repay(loanId, new BigDecimal("105.00"), owner);

        assertThat(response.outstandingPrincipal()).isEqualByComparingTo("0");
        assertThat(response.accruedInterest()).isEqualByComparingTo("0");
        assertThat(response.status()).isEqualTo(LoanStatus.PAID_OFF);
    }

    @Test
    void repayNeverTakesMoreThanTotalOwed() {
        UUID loanId = UUID.randomUUID();
        Loan loan = activeLoan(loanId, new BigDecimal("100.00"), new BigDecimal("5.00"));
        when(loanRepository.findById(loanId)).thenReturn(Optional.of(loan));
        Account account = account(new BigDecimal("500.00"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        LoanResponse response = loanService.repay(loanId, new BigDecimal("1000.00"), owner);

        assertThat(response.status()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(account.getBalance()).isEqualByComparingTo("395.00");
    }

    @Test
    void repayThrowsWhenLoanNotActive() {
        UUID loanId = UUID.randomUUID();
        Loan loan = activeLoan(loanId, BigDecimal.ZERO, BigDecimal.ZERO);
        loan.setStatus(LoanStatus.PAID_OFF);
        when(loanRepository.findById(loanId)).thenReturn(Optional.of(loan));

        assertThatThrownBy(() -> loanService.repay(loanId, new BigDecimal("10.00"), owner))
                .isInstanceOf(LoanNotActiveException.class);
    }

    @Test
    void accrueInterestAppliesSimpleDailyInterestToAllActiveLoans() {
        Loan loan = activeLoan(UUID.randomUUID(), new BigDecimal("1000.00"), BigDecimal.ZERO);
        loan.setLastAccrualAt(Instant.now().minus(java.time.Duration.ofDays(365)));
        when(loanRepository.findByStatus(LoanStatus.ACTIVE)).thenReturn(List.of(loan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        loanService.accrueInterest();

        // 1000 principal * 5% annual over ~365 days ~= 50.00
        assertThat(loan.getAccruedInterest()).isCloseTo(new BigDecimal("50.00"), org.assertj.core.data.Offset.offset(new BigDecimal("0.20")));
    }

    @Test
    void calculateAccrualIsSimpleInterestOverDays() {
        BigDecimal accrual = loanService.calculateAccrual(new BigDecimal("1000.00"), new BigDecimal("5.0"), 365);

        assertThat(accrual).isEqualByComparingTo("50.00000000");
    }
}
