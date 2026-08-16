package com.dcbate.tradingplatform.statement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.account.repository.AccountActivityRepository;
import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountActivity;
import com.dcbate.tradingplatform.domain.AccountActivityType;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.AccountType;
import com.dcbate.tradingplatform.domain.Loan;
import com.dcbate.tradingplatform.domain.LoanActivity;
import com.dcbate.tradingplatform.domain.LoanEventType;
import com.dcbate.tradingplatform.domain.LoanProductType;
import com.dcbate.tradingplatform.domain.LoanStatus;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.domain.Transfer;
import com.dcbate.tradingplatform.domain.TransferStatus;
import com.dcbate.tradingplatform.loan.repository.LoanActivityRepository;
import com.dcbate.tradingplatform.loan.repository.LoanRepository;
import com.dcbate.tradingplatform.payment.repository.PaymentRepository;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.statement.api.dto.BankStatementEntry;
import com.dcbate.tradingplatform.statement.api.dto.BankStatementResponse;
import com.dcbate.tradingplatform.statement.api.dto.StatementEntryType;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import com.dcbate.tradingplatform.transfer.repository.TransferRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class BankStatementServiceImplTest {

    private static final String CLIENT_ID = "client-1";

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountActivityRepository accountActivityRepository;

    @Mock
    private LoanActivityRepository loanActivityRepository;

    @Mock
    private LoanRepository loanRepository;

    @Mock
    private AccountRepository accountRepository;

    private BankStatementServiceImpl statementService;

    private final CallerPrincipal owner = new CallerPrincipal(CLIENT_ID, false);
    private final CallerPrincipal otherClient = new CallerPrincipal("client-2", false);

    @BeforeEach
    void setUp() {
        statementService = new BankStatementServiceImpl(
                orderRepository, paymentRepository, transferRepository,
                accountActivityRepository, loanActivityRepository, loanRepository, accountRepository);
        when(accountRepository.findByClientId(CLIENT_ID)).thenReturn(List.of());
        when(loanRepository.findByClientId(CLIENT_ID)).thenReturn(List.of());
        when(orderRepository.findByClientIdOrderByCreatedAtDesc(CLIENT_ID)).thenReturn(List.of());
        when(paymentRepository.findByClientIdOrderByCreatedAtDesc(CLIENT_ID)).thenReturn(List.of());
        when(transferRepository.findByFromClientIdOrToClientIdOrderByCreatedAtDesc(CLIENT_ID, CLIENT_ID)).thenReturn(List.of());
        when(accountActivityRepository.findByClientIdOrderByOccurredAtDesc(CLIENT_ID)).thenReturn(List.of());
        when(loanActivityRepository.findByClientIdOrderByOccurredAtDesc(CLIENT_ID)).thenReturn(List.of());
    }

    @Test
    void rejectsALookupForSomeoneElsesStatement() {
        assertThatThrownBy(() -> statementService.getStatement(CLIENT_ID, otherClient))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void mergesEveryDomainIntoOneChronologicallySortedFeed() {
        Instant now = Instant.now();
        UUID accountId = UUID.randomUUID();
        Account account = Account.builder()
                .accountId(accountId).clientId(CLIENT_ID).accountType(AccountType.CHECKING)
                .currency("GBP").balance(new BigDecimal("500")).status(AccountStatus.ACTIVE).createdAt(now)
                .build();
        when(accountRepository.findByClientId(CLIENT_ID)).thenReturn(List.of(account));

        Order order = Order.builder()
                .orderId(UUID.randomUUID()).clientId(CLIENT_ID).currencyPair("EUR/USD")
                .side(OrderSide.BUY).quantity(new BigDecimal("5")).price(new BigDecimal("1.0850"))
                .status(OrderStatus.FILLED).createdAt(now.minus(4, ChronoUnit.MINUTES))
                .build();
        when(orderRepository.findByClientIdOrderByCreatedAtDesc(CLIENT_ID)).thenReturn(List.of(order));

        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID()).clientId(CLIENT_ID).sourceAccountId(accountId)
                .amount(new BigDecimal("100")).status(PaymentStatus.SETTLED).idempotencyKey("k1")
                .country("DE").createdAt(now.minus(3, ChronoUnit.MINUTES))
                .build();
        when(paymentRepository.findByClientIdOrderByCreatedAtDesc(CLIENT_ID)).thenReturn(List.of(payment));

        Transfer transferOut = Transfer.builder()
                .transferId(UUID.randomUUID()).fromAccountId(accountId).toAccountId(UUID.randomUUID())
                .fromClientId(CLIENT_ID).toClientId("client-2").amount(new BigDecimal("20"))
                .status(TransferStatus.COMPLETED).createdAt(now.minus(2, ChronoUnit.MINUTES))
                .build();
        when(transferRepository.findByFromClientIdOrToClientIdOrderByCreatedAtDesc(CLIENT_ID, CLIENT_ID))
                .thenReturn(List.of(transferOut));

        AccountActivity deposit = AccountActivity.builder()
                .activityId(UUID.randomUUID()).accountId(accountId).clientId(CLIENT_ID)
                .type(AccountActivityType.DEPOSIT).amount(new BigDecimal("50")).balanceAfter(new BigDecimal("550"))
                .occurredAt(now.minus(1, ChronoUnit.MINUTES))
                .build();
        when(accountActivityRepository.findByClientIdOrderByOccurredAtDesc(CLIENT_ID)).thenReturn(List.of(deposit));

        UUID loanId = UUID.randomUUID();
        Loan loan = Loan.builder()
                .loanId(loanId).clientId(CLIENT_ID).accountId(accountId)
                .principal(new BigDecimal("1000")).outstandingPrincipal(new BigDecimal("1000"))
                .interestRateAnnualPercent(new BigDecimal("9.99")).productType(LoanProductType.PERSONAL_SHORT)
                .termMonths(12).accruedInterest(BigDecimal.ZERO).status(LoanStatus.ACTIVE)
                .createdAt(now).lastAccrualAt(now)
                .build();
        when(loanRepository.findByClientId(CLIENT_ID)).thenReturn(List.of(loan));
        LoanActivity origination = LoanActivity.builder()
                .activityId(UUID.randomUUID()).loanId(loanId).clientId(CLIENT_ID).type(LoanEventType.ORIGINATED)
                .amount(new BigDecimal("1000")).outstandingPrincipal(new BigDecimal("1000")).accruedInterest(BigDecimal.ZERO)
                .status(LoanStatus.ACTIVE).occurredAt(now)
                .build();
        when(loanActivityRepository.findByClientIdOrderByOccurredAtDesc(CLIENT_ID)).thenReturn(List.of(origination));

        BankStatementResponse response = statementService.getStatement(CLIENT_ID, owner);

        assertThat(response.clientId()).isEqualTo(CLIENT_ID);
        assertThat(response.entries()).hasSize(5);
        // Newest first.
        assertThat(response.entries().stream().map(BankStatementEntry::type).toList()).containsExactly(
                StatementEntryType.LOAN_ORIGINATED, StatementEntryType.DEPOSIT, StatementEntryType.TRANSFER_OUT,
                StatementEntryType.PAYMENT, StatementEntryType.FX_ORDER);

        BankStatementEntry depositEntry = response.entries().get(1);
        assertThat(depositEntry.amount()).isEqualByComparingTo("50");
        assertThat(depositEntry.currency()).isEqualTo("GBP");

        BankStatementEntry transferEntry = response.entries().get(2);
        assertThat(transferEntry.amount()).isEqualByComparingTo("-20");

        BankStatementEntry paymentEntry = response.entries().get(3);
        assertThat(paymentEntry.amount()).isEqualByComparingTo("-100");
        assertThat(paymentEntry.currency()).isNull();

        BankStatementEntry orderEntry = response.entries().get(4);
        assertThat(orderEntry.amount()).isNull();
    }

    @Test
    void aSelfTransferProducesBothAnOutgoingAndIncomingLeg() {
        Instant now = Instant.now();
        Transfer selfTransfer = Transfer.builder()
                .transferId(UUID.randomUUID()).fromAccountId(UUID.randomUUID()).toAccountId(UUID.randomUUID())
                .fromClientId(CLIENT_ID).toClientId(CLIENT_ID).amount(new BigDecimal("30"))
                .status(TransferStatus.COMPLETED).createdAt(now)
                .build();
        when(transferRepository.findByFromClientIdOrToClientIdOrderByCreatedAtDesc(CLIENT_ID, CLIENT_ID))
                .thenReturn(List.of(selfTransfer));

        BankStatementResponse response = statementService.getStatement(CLIENT_ID, owner);

        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries().stream().map(BankStatementEntry::type).toList())
                .containsExactlyInAnyOrder(StatementEntryType.TRANSFER_OUT, StatementEntryType.TRANSFER_IN);
    }
}
