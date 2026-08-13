package com.dcbate.tradingplatform.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.NotificationType;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.domain.Settlement;
import com.dcbate.tradingplatform.domain.SettlementStatus;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.NotificationEvent;
import com.dcbate.tradingplatform.kafka.event.PaymentValidatedEvent;
import com.dcbate.tradingplatform.payment.repository.PaymentRepository;
import com.dcbate.tradingplatform.payment.repository.SettlementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementServiceImplTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private BankClearingClient bankClearingClient;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    private SettlementServiceImpl settlementService;

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "settlements", "fraud-alerts", "notifications", "notifications-dlq");
        settlementService = new SettlementServiceImpl(
                paymentRepository, settlementRepository, ledgerService, bankClearingClient, kafkaEventPublisher, topics);
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Payment payment(BigDecimal amount) {
        return Payment.builder()
                .paymentId(UUID.randomUUID()).clientId("client-1").amount(amount).status(PaymentStatus.PENDING)
                .idempotencyKey("key").country("US").createdAt(Instant.now())
                .build();
    }

    private PaymentValidatedEvent eventFor(Payment payment) {
        return new PaymentValidatedEvent(payment.getPaymentId(), payment.getClientId(), payment.getAmount(), payment.getCountry(), payment.getCreatedAt());
    }

    @Test
    void successfulClearingSettlesThePayment() {
        Payment payment = payment(new BigDecimal("100.00"));
        when(paymentRepository.findById(payment.getPaymentId())).thenReturn(Optional.of(payment));
        when(bankClearingClient.clear(payment)).thenReturn(true);

        settlementService.process(eventFor(payment));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SETTLED);
        verify(ledgerService).recordDoubleEntry(payment);
        verify(ledgerService, never()).reverseEntries(any());

        ArgumentCaptor<Settlement> settlementCaptor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository, org.mockito.Mockito.times(2)).save(settlementCaptor.capture());
        assertThat(settlementCaptor.getValue().getStatus()).isEqualTo(SettlementStatus.CLEARED);

        ArgumentCaptor<NotificationEvent> notificationCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(kafkaEventPublisher).publish(eq("notifications"), eq("client-1"), notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().type()).isEqualTo(NotificationType.PAYMENT_SETTLED);
    }

    @Test
    void failedClearingCompensatesThePayment() {
        Payment payment = payment(new BigDecimal("600000.00"));
        when(paymentRepository.findById(payment.getPaymentId())).thenReturn(Optional.of(payment));
        when(bankClearingClient.clear(payment)).thenReturn(false);

        settlementService.process(eventFor(payment));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(ledgerService).reverseEntries(payment);

        ArgumentCaptor<Settlement> settlementCaptor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository, org.mockito.Mockito.times(2)).save(settlementCaptor.capture());
        assertThat(settlementCaptor.getValue().getStatus()).isEqualTo(SettlementStatus.COMPENSATED);

        ArgumentCaptor<NotificationEvent> notificationCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(kafkaEventPublisher).publish(eq("notifications"), eq("client-1"), notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().type()).isEqualTo(NotificationType.PAYMENT_FAILED);
    }
}
