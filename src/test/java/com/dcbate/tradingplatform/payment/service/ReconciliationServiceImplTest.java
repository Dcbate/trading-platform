package com.dcbate.tradingplatform.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.config.ReconciliationProperties;
import com.dcbate.tradingplatform.domain.LedgerEntry;
import com.dcbate.tradingplatform.domain.LedgerEntryType;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.domain.ReconciliationAlert;
import com.dcbate.tradingplatform.domain.Settlement;
import com.dcbate.tradingplatform.domain.SettlementStatus;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.NotificationEvent;
import com.dcbate.tradingplatform.payment.repository.LedgerEntryRepository;
import com.dcbate.tradingplatform.payment.repository.PaymentRepository;
import com.dcbate.tradingplatform.payment.repository.ReconciliationAlertRepository;
import com.dcbate.tradingplatform.payment.repository.SettlementRepository;
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

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceImplTest {

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private ReconciliationAlertRepository reconciliationAlertRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    private ReconciliationServiceImpl reconciliationService;

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "settlements", "fraud-alerts", "notifications", "notifications-dlq",
                "account-activity", "transfers", "loans");
        ReconciliationProperties reconciliationProperties = new ReconciliationProperties(new BigDecimal("1.00"), "0 0 2 * * *");
        reconciliationService = new ReconciliationServiceImpl(
                settlementRepository, paymentRepository, ledgerEntryRepository, reconciliationAlertRepository,
                kafkaEventPublisher, topics, reconciliationProperties);
    }

    private Settlement clearedSettlement(UUID paymentId) {
        return Settlement.builder().settlementId(UUID.randomUUID()).paymentId(paymentId)
                .status(SettlementStatus.CLEARED).attemptCount(1).createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }

    private LedgerEntry entry(UUID paymentId, LedgerEntryType type, String amount) {
        return LedgerEntry.builder().entryId(UUID.randomUUID()).paymentId(paymentId).accountId("acct")
                .entryType(type).amount(new BigDecimal(amount)).createdAt(Instant.now())
                .build();
    }

    @Test
    void balancedLedgerRaisesNoAlert() {
        UUID paymentId = UUID.randomUUID();
        when(settlementRepository.findByStatus(SettlementStatus.CLEARED)).thenReturn(List.of(clearedSettlement(paymentId)));
        when(reconciliationAlertRepository.existsByPaymentId(paymentId)).thenReturn(false);
        when(ledgerEntryRepository.findByPaymentId(paymentId))
                .thenReturn(List.of(entry(paymentId, LedgerEntryType.DEBIT, "100.00"), entry(paymentId, LedgerEntryType.CREDIT, "100.00")));

        reconciliationService.reconcile();

        verify(reconciliationAlertRepository, never()).save(any());
    }

    @Test
    void smallDiscrepancyAutoResolvesWithoutAlert() {
        UUID paymentId = UUID.randomUUID();
        when(settlementRepository.findByStatus(SettlementStatus.CLEARED)).thenReturn(List.of(clearedSettlement(paymentId)));
        when(reconciliationAlertRepository.existsByPaymentId(paymentId)).thenReturn(false);
        when(ledgerEntryRepository.findByPaymentId(paymentId))
                .thenReturn(List.of(entry(paymentId, LedgerEntryType.DEBIT, "100.00"), entry(paymentId, LedgerEntryType.CREDIT, "99.50")));

        reconciliationService.reconcile();

        verify(reconciliationAlertRepository, never()).save(any());
    }

    @Test
    void largeDiscrepancyPersistsAlertAndNotifies() {
        UUID paymentId = UUID.randomUUID();
        when(settlementRepository.findByStatus(SettlementStatus.CLEARED)).thenReturn(List.of(clearedSettlement(paymentId)));
        when(reconciliationAlertRepository.existsByPaymentId(paymentId)).thenReturn(false);
        when(ledgerEntryRepository.findByPaymentId(paymentId))
                .thenReturn(List.of(entry(paymentId, LedgerEntryType.DEBIT, "100.00"), entry(paymentId, LedgerEntryType.CREDIT, "50.00")));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(
                Payment.builder().paymentId(paymentId).clientId("client-1").amount(new BigDecimal("100.00"))
                        .status(PaymentStatus.SETTLED).idempotencyKey("key").country("US").createdAt(Instant.now()).build()));

        reconciliationService.reconcile();

        verify(reconciliationAlertRepository).save(any(ReconciliationAlert.class));
        verify(kafkaEventPublisher).publish(eq("notifications"), eq("client-1"), any(NotificationEvent.class));
    }

    @Test
    void alreadyAlertedPaymentIsSkipped() {
        UUID paymentId = UUID.randomUUID();
        when(settlementRepository.findByStatus(SettlementStatus.CLEARED)).thenReturn(List.of(clearedSettlement(paymentId)));
        when(reconciliationAlertRepository.existsByPaymentId(paymentId)).thenReturn(true);

        reconciliationService.reconcile();

        verify(ledgerEntryRepository, never()).findByPaymentId(any());
        verify(reconciliationAlertRepository, never()).save(any());
    }
}
