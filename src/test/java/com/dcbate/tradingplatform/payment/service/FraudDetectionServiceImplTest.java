package com.dcbate.tradingplatform.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.ai.AnomalyContext;
import com.dcbate.tradingplatform.ai.AnomalyDetector;
import com.dcbate.tradingplatform.ai.AnomalyResult;
import com.dcbate.tradingplatform.config.FraudProperties;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.FraudFlag;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.exception.InvalidPaymentStateException;
import com.dcbate.tradingplatform.exception.PaymentNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.FraudAlertEvent;
import com.dcbate.tradingplatform.kafka.event.NotificationEvent;
import com.dcbate.tradingplatform.kafka.event.PaymentEvent;
import com.dcbate.tradingplatform.kafka.event.PaymentValidatedEvent;
import com.dcbate.tradingplatform.payment.repository.FraudFlagRepository;
import com.dcbate.tradingplatform.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceImplTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private FraudFlagRepository fraudFlagRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private AnomalyDetector anomalyDetector;

    private FraudDetectionServiceImpl fraudDetectionService;

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "settlements", "fraud-alerts", "notifications", "notifications-dlq",
                "account-activity", "transfers", "loans");
        FraudProperties fraudProperties = new FraudProperties(3, 5, 60, 2);
        fraudDetectionService = new FraudDetectionServiceImpl(
                paymentRepository, fraudFlagRepository, kafkaEventPublisher, topics, fraudProperties,
                new PaymentVelocityTracker(), anomalyDetector);
    }

    private PaymentEvent paymentEvent(String clientId, String amount, String country) {
        return new PaymentEvent(UUID.randomUUID(), clientId, new BigDecimal(amount), country, Instant.now());
    }

    @Test
    void withinLimitsPassesToValidated() {
        when(paymentRepository.averageSettledAmountByClientId(anyString())).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.findTopByClientIdAndPaymentIdNotOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        fraudDetectionService.evaluate(paymentEvent("client-1", "100.00", "US"));

        verify(kafkaEventPublisher).publish(eq("payments-validated"), eq("client-1"), any(PaymentValidatedEvent.class));
        verify(fraudFlagRepository, never()).save(any());
    }

    @Test
    void exceedingVelocityLimitBlocksThePayment() {
        when(paymentRepository.averageSettledAmountByClientId(anyString())).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.findTopByClientIdAndPaymentIdNotOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());
        when(anomalyDetector.explain(any())).thenAnswer(inv -> new AnomalyResult(inv.getArgument(0, AnomalyContext.class).description(), false));
        when(paymentRepository.findById(any())).thenReturn(Optional.of(Payment.builder().status(PaymentStatus.PENDING).build()));

        for (int i = 0; i < 5; i++) {
            fraudDetectionService.evaluate(paymentEvent("client-2", "10.00", "US"));
        }
        PaymentEvent sixth = paymentEvent("client-2", "10.00", "US");
        fraudDetectionService.evaluate(sixth);

        verify(kafkaEventPublisher, times(1)).publish(eq("fraud-alerts"), eq("client-2"), any(FraudAlertEvent.class));
        verify(fraudFlagRepository).save(any(FraudFlag.class));
    }

    @Test
    void fastCountryChangeBlocksThePayment() {
        when(anomalyDetector.explain(any())).thenAnswer(inv -> new AnomalyResult(inv.getArgument(0, AnomalyContext.class).description(), false));
        when(paymentRepository.findById(any())).thenReturn(Optional.of(Payment.builder().status(PaymentStatus.PENDING).build()));

        Payment previous = Payment.builder()
                .paymentId(UUID.randomUUID()).clientId("client-3").country("US").createdAt(Instant.now().minusSeconds(60))
                .build();
        PaymentEvent event = paymentEvent("client-3", "10.00", "FR");
        when(paymentRepository.findTopByClientIdAndPaymentIdNotOrderByCreatedAtDesc("client-3", event.paymentId()))
                .thenReturn(Optional.of(previous));

        fraudDetectionService.evaluate(event);

        verify(kafkaEventPublisher).publish(eq("fraud-alerts"), eq("client-3"), any(FraudAlertEvent.class));
    }

    @Test
    void amountFarAboveClientAverageIsSentForReview() {
        when(paymentRepository.averageSettledAmountByClientId(anyString())).thenReturn(new BigDecimal("10.00"));
        when(paymentRepository.findTopByClientIdAndPaymentIdNotOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());
        when(anomalyDetector.explain(any())).thenAnswer(inv -> new AnomalyResult(inv.getArgument(0, AnomalyContext.class).description(), false));
        Payment payment = Payment.builder().status(PaymentStatus.PENDING).build();
        when(paymentRepository.findById(any())).thenReturn(Optional.of(payment));

        fraudDetectionService.evaluate(paymentEvent("client-4", "1000.00", "US"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNDER_REVIEW);
        verify(kafkaEventPublisher).publish(eq("fraud-alerts"), eq("client-4"), any(FraudAlertEvent.class));
    }

    private Payment underReviewPayment() {
        return Payment.builder()
                .paymentId(UUID.randomUUID()).clientId("client-5").amount(new BigDecimal("500.00"))
                .status(PaymentStatus.UNDER_REVIEW).idempotencyKey("key").country("US").createdAt(Instant.now())
                .build();
    }

    @Test
    void approveReleasesAnUnderReviewPaymentToSettlement() {
        Payment payment = underReviewPayment();
        when(paymentRepository.findById(payment.getPaymentId())).thenReturn(Optional.of(payment));

        fraudDetectionService.approve(payment.getPaymentId());

        verify(kafkaEventPublisher).publish(eq("payments-validated"), eq("client-5"), any(PaymentValidatedEvent.class));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNDER_REVIEW);
    }

    @Test
    void rejectBlocksAnUnderReviewPayment() {
        Payment payment = underReviewPayment();
        when(paymentRepository.findById(payment.getPaymentId())).thenReturn(Optional.of(payment));

        fraudDetectionService.reject(payment.getPaymentId());

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.BLOCKED);
        verify(kafkaEventPublisher).publish(eq("notifications"), eq("client-5"), any(NotificationEvent.class));
    }

    @Test
    void approveRejectsAPaymentThatIsNotUnderReview() {
        Payment payment = Payment.builder().paymentId(UUID.randomUUID()).status(PaymentStatus.SETTLED).build();
        when(paymentRepository.findById(payment.getPaymentId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> fraudDetectionService.approve(payment.getPaymentId()))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void approveThrowsWhenPaymentDoesNotExist() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fraudDetectionService.approve(paymentId)).isInstanceOf(PaymentNotFoundException.class);
    }
}
