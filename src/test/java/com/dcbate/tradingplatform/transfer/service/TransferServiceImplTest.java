package com.dcbate.tradingplatform.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.AccountType;
import com.dcbate.tradingplatform.domain.Transfer;
import com.dcbate.tradingplatform.domain.TransferStatus;
import com.dcbate.tradingplatform.exception.CurrencyMismatchException;
import com.dcbate.tradingplatform.exception.InsufficientFundsException;
import com.dcbate.tradingplatform.exception.TransferNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.transfer.api.dto.TransferRequest;
import com.dcbate.tradingplatform.transfer.api.dto.TransferResponse;
import com.dcbate.tradingplatform.transfer.repository.TransferRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class TransferServiceImplTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    private TransferServiceImpl transferService;

    private final UUID fromAccountId = UUID.randomUUID();
    private final UUID toAccountId = UUID.randomUUID();
    private final CallerPrincipal sender = new CallerPrincipal("client-1", false);
    private final CallerPrincipal stranger = new CallerPrincipal("client-3", false);

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "fraud-alerts", "notifications",
                "account-activity", "transfers", "loans");
        transferService = new TransferServiceImpl(
                transferRepository, accountRepository, kafkaEventPublisher, topics, new SimpleMeterRegistry());
    }

    private Account account(UUID accountId, String clientId, String currency, BigDecimal balance) {
        return Account.builder()
                .accountId(accountId).clientId(clientId).accountType(AccountType.CHECKING)
                .currency(currency).balance(balance).status(AccountStatus.ACTIVE).createdAt(Instant.now())
                .build();
    }

    @Test
    void transferMovesBalanceBetweenAccounts() {
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(account(fromAccountId, "client-1", "USD", new BigDecimal("100.00"))));
        when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(account(toAccountId, "client-2", "USD", new BigDecimal("10.00"))));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transferRepository.save(any(Transfer.class))).thenAnswer(inv -> inv.getArgument(0));

        TransferResponse response = transferService.transfer(
                new TransferRequest(fromAccountId, toAccountId, new BigDecimal("40.00")), sender);

        assertThat(response.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(response.fromClientId()).isEqualTo("client-1");
        assertThat(response.toClientId()).isEqualTo("client-2");
    }

    @Test
    void transferDeniedForNonOwnerOfSourceAccount() {
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(account(fromAccountId, "client-1", "USD", new BigDecimal("100.00"))));

        assertThatThrownBy(() -> transferService.transfer(
                new TransferRequest(fromAccountId, toAccountId, new BigDecimal("40.00")), stranger))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void transferThrowsOnCurrencyMismatch() {
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(account(fromAccountId, "client-1", "USD", new BigDecimal("100.00"))));
        when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(account(toAccountId, "client-2", "EUR", new BigDecimal("10.00"))));

        assertThatThrownBy(() -> transferService.transfer(
                new TransferRequest(fromAccountId, toAccountId, new BigDecimal("40.00")), sender))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void transferThrowsOnInsufficientFunds() {
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(account(fromAccountId, "client-1", "USD", new BigDecimal("10.00"))));
        when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(account(toAccountId, "client-2", "USD", new BigDecimal("10.00"))));

        assertThatThrownBy(() -> transferService.transfer(
                new TransferRequest(fromAccountId, toAccountId, new BigDecimal("40.00")), sender))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void getTransferThrowsWhenMissing() {
        UUID transferId = UUID.randomUUID();
        when(transferRepository.findById(transferId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getTransfer(transferId, sender)).isInstanceOf(TransferNotFoundException.class);
    }

    @Test
    void getTransferDeniedForNonParty() {
        UUID transferId = UUID.randomUUID();
        Transfer transfer = Transfer.builder()
                .transferId(transferId).fromAccountId(fromAccountId).toAccountId(toAccountId)
                .fromClientId("client-1").toClientId("client-2").amount(new BigDecimal("40.00"))
                .status(TransferStatus.COMPLETED).createdAt(Instant.now())
                .build();
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> transferService.getTransfer(transferId, stranger)).isInstanceOf(AccessDeniedException.class);
    }
}
