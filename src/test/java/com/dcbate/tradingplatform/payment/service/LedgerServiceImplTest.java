package com.dcbate.tradingplatform.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.AccountType;
import com.dcbate.tradingplatform.domain.LedgerEntry;
import com.dcbate.tradingplatform.domain.LedgerEntryType;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.LedgerEntryEvent;
import com.dcbate.tradingplatform.payment.repository.LedgerEntryRepository;
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

@ExtendWith(MockitoExtension.class)
class LedgerServiceImplTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    private LedgerServiceImpl ledgerService;

    private final UUID sourceAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "settlements", "fraud-alerts", "notifications", "notifications-dlq",
                "account-activity", "transfers", "loans");
        ledgerService = new LedgerServiceImpl(ledgerEntryRepository, accountRepository, kafkaEventPublisher, topics);
        when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Payment payment() {
        return Payment.builder()
                .paymentId(UUID.randomUUID()).clientId("client-1").sourceAccountId(sourceAccountId)
                .amount(new BigDecimal("100.00"))
                .status(PaymentStatus.RESERVED).idempotencyKey("key").country("US").createdAt(Instant.now())
                .build();
    }

    private Account account() {
        return Account.builder()
                .accountId(sourceAccountId).clientId("client-1").accountType(AccountType.CHECKING)
                .currency("USD").balance(new BigDecimal("1000.00")).status(AccountStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void recordDoubleEntryWritesOneDebitAndOneCredit() {
        Payment payment = payment();
        when(accountRepository.findById(sourceAccountId)).thenReturn(Optional.of(account()));

        ledgerService.recordDoubleEntry(payment);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        List<LedgerEntry> entries = captor.getAllValues();

        assertThat(entries).extracting(LedgerEntry::getEntryType)
                .containsExactlyInAnyOrder(LedgerEntryType.DEBIT, LedgerEntryType.CREDIT);
        assertThat(entries).allMatch(e -> e.getAmount().compareTo(payment.getAmount()) == 0);
        verify(kafkaEventPublisher, times(2)).publish(eq("ledger-entries"), any(), any(LedgerEntryEvent.class));
    }

    @Test
    void reverseEntriesFlipsEachOriginalEntryType() {
        Payment payment = payment();
        LedgerEntry originalDebit = LedgerEntry.builder()
                .entryId(UUID.randomUUID()).paymentId(payment.getPaymentId()).accountId("client:client-1")
                .entryType(LedgerEntryType.DEBIT).amount(payment.getAmount()).createdAt(Instant.now())
                .build();
        LedgerEntry originalCredit = LedgerEntry.builder()
                .entryId(UUID.randomUUID()).paymentId(payment.getPaymentId()).accountId("platform:settlement")
                .entryType(LedgerEntryType.CREDIT).amount(payment.getAmount()).createdAt(Instant.now())
                .build();
        when(ledgerEntryRepository.findByPaymentId(payment.getPaymentId())).thenReturn(List.of(originalDebit, originalCredit));

        ledgerService.reverseEntries(payment);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(LedgerEntry::getEntryType)
                .containsExactlyInAnyOrder(LedgerEntryType.CREDIT, LedgerEntryType.DEBIT);
    }
}
