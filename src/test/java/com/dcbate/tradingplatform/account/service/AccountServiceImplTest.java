package com.dcbate.tradingplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.account.api.dto.AccountRequest;
import com.dcbate.tradingplatform.account.api.dto.AccountResponse;
import com.dcbate.tradingplatform.account.api.dto.BalanceSummaryResponse;
import com.dcbate.tradingplatform.account.api.dto.CloseAccountRequest;
import com.dcbate.tradingplatform.account.api.dto.ConvertRequest;
import com.dcbate.tradingplatform.account.api.dto.CurrencyBalance;
import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.activity.event.ActivityRecordedEvent;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.ActivityType;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.AccountType;
import com.dcbate.tradingplatform.exception.AccountNotActiveException;
import com.dcbate.tradingplatform.exception.AccountNotFoundException;
import com.dcbate.tradingplatform.exception.CurrencyMismatchException;
import com.dcbate.tradingplatform.exception.InsufficientFundsException;
import com.dcbate.tradingplatform.exception.InvalidAccountClosureException;
import com.dcbate.tradingplatform.exception.RateUnavailableException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.trading.service.PriceFeedService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private PriceFeedService priceFeedService;

    private AccountServiceImpl accountService;

    private final CallerPrincipal owner = new CallerPrincipal("client-1", false);
    private final CallerPrincipal otherClient = new CallerPrincipal("client-2", false);
    private final CallerPrincipal staff = new CallerPrincipal("staff-1", true);

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "fraud-alerts", "notifications",
                "account-activity", "transfers", "loans");
        accountService = new AccountServiceImpl(accountRepository, eventPublisher, kafkaEventPublisher, topics, priceFeedService);
    }

    private Account account(UUID accountId, String clientId, String currency, BigDecimal balance) {
        return Account.builder()
                .accountId(accountId).clientId(clientId).accountType(AccountType.CHECKING)
                .currency(currency).balance(balance).status(AccountStatus.ACTIVE).createdAt(Instant.now())
                .build();
    }

    @Test
    void openAccountPersistsAsActiveWithOpeningBalance() {
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.openAccount(
                new AccountRequest("client-1", AccountType.CHECKING, "USD", "Rent fund", new BigDecimal("1000.00")), owner);

        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.balance()).isEqualByComparingTo("1000.00");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.nickname()).isEqualTo("Rent fund");
    }

    @Test
    void openAccountDeniedForAnotherClient() {
        AccountRequest request = new AccountRequest("client-2", AccountType.CHECKING, "USD", null, BigDecimal.ZERO);

        assertThatThrownBy(() -> accountService.openAccount(request, owner)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getAccountThrowsWhenMissing() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(accountId, owner)).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getAccountDeniedForNonOwner() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", BigDecimal.TEN)));

        assertThatThrownBy(() -> accountService.getAccount(accountId, otherClient)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getAccountAllowedForStaff() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", BigDecimal.TEN)));

        AccountResponse response = accountService.getAccount(accountId, staff);

        assertThat(response.accountId()).isEqualTo(accountId);
    }

    @Test
    void listAccountsForClientReturnsAllOfTheirAccounts() {
        Account account = account(UUID.randomUUID(), "client-2", "EUR", new BigDecimal("500.00"));
        when(accountRepository.findByClientId("client-2")).thenReturn(List.of(account));

        List<AccountResponse> responses = accountService.listAccountsForClient("client-2", otherClient);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).clientId()).isEqualTo("client-2");
    }

    @Test
    void getBalanceSummaryGroupsActiveAccountsByCurrencyExcludingClosedAndFrozen() {
        Account checking = account(UUID.randomUUID(), "client-1", "USD", new BigDecimal("2500.00"));
        Account savings = account(UUID.randomUUID(), "client-1", "USD", new BigDecimal("5000.00"));
        Account euroAccount = account(UUID.randomUUID(), "client-1", "EUR", new BigDecimal("300.00"));
        Account closed = account(UUID.randomUUID(), "client-1", "USD", new BigDecimal("999.00"));
        closed.setStatus(AccountStatus.CLOSED);
        Account frozen = account(UUID.randomUUID(), "client-1", "USD", new BigDecimal("999.00"));
        frozen.setStatus(AccountStatus.FROZEN);
        when(accountRepository.findByClientId("client-1")).thenReturn(List.of(checking, savings, euroAccount, closed, frozen));

        BalanceSummaryResponse summary = accountService.getBalanceSummary("client-1", owner);

        assertThat(summary.clientId()).isEqualTo("client-1");
        assertThat(summary.activeAccountCount()).isEqualTo(3);
        assertThat(summary.balances()).containsExactly(
                new CurrencyBalance("EUR", new BigDecimal("300.00"), 1),
                new CurrencyBalance("USD", new BigDecimal("7500.00"), 2));
    }

    @Test
    void getBalanceSummaryDeniedForNonOwner() {
        assertThatThrownBy(() -> accountService.getBalanceSummary("client-1", otherClient)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void depositIncreasesBalance() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", new BigDecimal("100.00"))));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.deposit(accountId, new BigDecimal("50.00"), owner);

        assertThat(response.balance()).isEqualByComparingTo("150.00");

        ArgumentCaptor<ActivityRecordedEvent> captor = ArgumentCaptor.forClass(ActivityRecordedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(ActivityType.DEPOSIT);
        assertThat(captor.getValue().amount()).isEqualByComparingTo("50.00");
        assertThat(captor.getValue().currency()).isEqualTo("USD");
        assertThat(captor.getValue().accountId()).isEqualTo(accountId);
    }

    @Test
    void withdrawDecreasesBalance() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", new BigDecimal("100.00"))));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.withdraw(accountId, new BigDecimal("40.00"), owner);

        assertThat(response.balance()).isEqualByComparingTo("60.00");
    }

    @Test
    void withdrawThrowsWhenInsufficientFunds() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", new BigDecimal("10.00"))));

        assertThatThrownBy(() -> accountService.withdraw(accountId, new BigDecimal("40.00"), owner))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void withdrawDeniedForNonOwner() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", new BigDecimal("100.00"))));

        assertThatThrownBy(() -> accountService.withdraw(accountId, new BigDecimal("10.00"), otherClient))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void convertUsesDirectCachedRate() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        when(accountRepository.findById(fromId)).thenReturn(Optional.of(account(fromId, "client-1", "EUR", new BigDecimal("100.00"))));
        when(accountRepository.findById(toId)).thenReturn(Optional.of(account(toId, "client-1", "USD", new BigDecimal("0.00"))));
        when(priceFeedService.currentPrice("EUR/USD")).thenReturn(Optional.of(new BigDecimal("1.10")));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.convert(fromId, new ConvertRequest(toId, new BigDecimal("100.00")), owner);

        assertThat(response.balance()).isEqualByComparingTo("110.00");
    }

    @Test
    void convertFallsBackToInverseCachedRate() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        when(accountRepository.findById(fromId)).thenReturn(Optional.of(account(fromId, "client-1", "USD", new BigDecimal("110.00"))));
        when(accountRepository.findById(toId)).thenReturn(Optional.of(account(toId, "client-1", "EUR", new BigDecimal("0.00"))));
        when(priceFeedService.currentPrice("USD/EUR")).thenReturn(Optional.empty());
        when(priceFeedService.currentPrice("EUR/USD")).thenReturn(Optional.of(new BigDecimal("1.10")));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.convert(fromId, new ConvertRequest(toId, new BigDecimal("110.00")), owner);

        assertThat(response.balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void convertThrowsWhenNoRateAvailable() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        when(accountRepository.findById(fromId)).thenReturn(Optional.of(account(fromId, "client-1", "EUR", new BigDecimal("100.00"))));
        when(accountRepository.findById(toId)).thenReturn(Optional.of(account(toId, "client-1", "JPY", new BigDecimal("0.00"))));
        when(priceFeedService.currentPrice("EUR/JPY")).thenReturn(Optional.empty());
        when(priceFeedService.currentPrice("JPY/EUR")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.convert(fromId, new ConvertRequest(toId, new BigDecimal("100.00")), owner))
                .isInstanceOf(RateUnavailableException.class);
    }

    @Test
    void closeAccountWithZeroBalanceNeedsNoDestination() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", BigDecimal.ZERO)));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.closeAccount(accountId, CloseAccountRequest.EMPTY, owner);

        assertThat(response.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void closeAccountWithBalanceSweepsToDestinationAndClosesSource() {
        UUID accountId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", new BigDecimal("250.00"))));
        when(accountRepository.findById(destinationId)).thenReturn(Optional.of(account(destinationId, "client-2", "USD", new BigDecimal("10.00"))));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.closeAccount(accountId, new CloseAccountRequest(destinationId), owner);

        assertThat(response.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void closeAccountWithBalanceAndNoDestinationThrows() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", new BigDecimal("50.00"))));

        assertThatThrownBy(() -> accountService.closeAccount(accountId, CloseAccountRequest.EMPTY, owner))
                .isInstanceOf(InvalidAccountClosureException.class);
    }

    @Test
    void closeAccountIntoItselfThrows() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", new BigDecimal("50.00"))));

        assertThatThrownBy(() -> accountService.closeAccount(accountId, new CloseAccountRequest(accountId), owner))
                .isInstanceOf(InvalidAccountClosureException.class);
    }

    @Test
    void closeAccountRejectsCurrencyMismatchedDestination() {
        UUID accountId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", new BigDecimal("50.00"))));
        when(accountRepository.findById(destinationId)).thenReturn(Optional.of(account(destinationId, "client-2", "EUR", BigDecimal.ZERO)));

        assertThatThrownBy(() -> accountService.closeAccount(accountId, new CloseAccountRequest(destinationId), owner))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void closeAccountRejectsAnInactiveDestination() {
        UUID accountId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        Account frozenDestination = account(destinationId, "client-2", "USD", BigDecimal.ZERO);
        frozenDestination.setStatus(AccountStatus.FROZEN);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", new BigDecimal("50.00"))));
        when(accountRepository.findById(destinationId)).thenReturn(Optional.of(frozenDestination));

        assertThatThrownBy(() -> accountService.closeAccount(accountId, new CloseAccountRequest(destinationId), owner))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void closeAccountDeniedForNonOwner() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "client-1", "USD", BigDecimal.ZERO)));

        assertThatThrownBy(() -> accountService.closeAccount(accountId, CloseAccountRequest.EMPTY, otherClient))
                .isInstanceOf(AccessDeniedException.class);
    }
}
