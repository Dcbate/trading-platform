package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.ai.AnomalyContext;
import com.dcbate.tradingplatform.ai.AnomalyDetector;
import com.dcbate.tradingplatform.ai.AnomalyResult;
import com.dcbate.tradingplatform.config.FraudProperties;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.FraudAction;
import com.dcbate.tradingplatform.domain.FraudFlag;
import com.dcbate.tradingplatform.domain.NotificationType;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.RiskLevel;
import com.dcbate.tradingplatform.exception.InvalidPaymentStateException;
import com.dcbate.tradingplatform.exception.PaymentNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.FraudAlertEvent;
import com.dcbate.tradingplatform.kafka.event.NotificationEvent;
import com.dcbate.tradingplatform.kafka.event.PaymentEvent;
import com.dcbate.tradingplatform.kafka.event.PaymentValidatedEvent;
import com.dcbate.tradingplatform.payment.repository.FraudFlagRepository;
import com.dcbate.tradingplatform.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rules run in order via {@code Optional.or()} — velocity, then country-change, then
 * amount-anomaly — and the first match wins; a payment is never checked against a rule after one
 * has already fired.
 *
 * @see FraudDetectionService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudDetectionServiceImpl implements FraudDetectionService {

    private final PaymentRepository paymentRepository;
    private final FraudFlagRepository fraudFlagRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;
    private final FraudProperties fraudProperties;
    private final PaymentVelocityTracker velocityTracker;
    private final AnomalyDetector anomalyDetector;
    private final MeterRegistry meterRegistry;

    private record FraudFinding(String reason, RiskLevel riskLevel, FraudAction action) {
    }

    @Override
    @Transactional
    public void evaluate(PaymentEvent event) {
        // Includes the AnomalyDetector (Claude) call inside flag() — this is the metric the
        // "fraud detection latency > 2s -> check Claude API" alert watches.
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Optional<FraudFinding> finding =
                    checkVelocity(event).or(() -> checkCountryChange(event)).or(() -> checkAmountAnomaly(event));

            finding.ifPresentOrElse(f -> flag(event, f), () -> pass(event));
        } finally {
            sample.stop(Timer.builder("fraud.detection.latency").publishPercentileHistogram().register(meterRegistry));
        }
    }

    private Optional<FraudFinding> checkVelocity(PaymentEvent event) {
        int count = velocityTracker.recordAndCount(
                event.clientId(), Instant.now(), Duration.ofSeconds(fraudProperties.windowSeconds()));
        if (count > fraudProperties.maxPaymentsPerWindow()) {
            return Optional.of(new FraudFinding(
                    "%d payments from client in the last %ds exceeds the limit of %d"
                            .formatted(count, fraudProperties.windowSeconds(), fraudProperties.maxPaymentsPerWindow()),
                    RiskLevel.HIGH,
                    FraudAction.BLOCK));
        }
        return Optional.empty();
    }

    private Optional<FraudFinding> checkCountryChange(PaymentEvent event) {
        Duration window = Duration.ofHours(fraudProperties.countryChangeWindowHours());
        return paymentRepository
                .findTopByClientIdAndPaymentIdNotOrderByCreatedAtDesc(event.clientId(), event.paymentId())
                .filter(previous -> !previous.getCountry().equals(event.country()))
                .filter(previous -> Duration.between(previous.getCreatedAt(), event.createdAt()).compareTo(window) < 0)
                .map(previous -> new FraudFinding(
                        "Country changed from %s to %s within %s of the previous payment"
                                .formatted(previous.getCountry(), event.country(),
                                        Duration.between(previous.getCreatedAt(), event.createdAt())),
                        RiskLevel.HIGH,
                        FraudAction.BLOCK));
    }

    private Optional<FraudFinding> checkAmountAnomaly(PaymentEvent event) {
        BigDecimal average = paymentRepository.averageSettledAmountByClientId(event.clientId());
        if (average.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal threshold = average.multiply(BigDecimal.valueOf(fraudProperties.amountMultiplierThreshold()));
        if (event.amount().compareTo(threshold) > 0) {
            return Optional.of(new FraudFinding(
                    "Amount %s exceeds %sx the client's average settled payment of %s"
                            .formatted(event.amount(), fraudProperties.amountMultiplierThreshold(), average),
                    RiskLevel.MEDIUM,
                    FraudAction.REVIEW));
        }
        return Optional.empty();
    }

    private void flag(PaymentEvent event, FraudFinding finding) {
        AnomalyResult enriched = anomalyDetector.explain(new AnomalyContext("payment:" + event.paymentId(), finding.reason()));

        paymentRepository.findById(event.paymentId()).ifPresent(payment -> {
            payment.setStatus(finding.action() == FraudAction.BLOCK ? PaymentStatus.BLOCKED : PaymentStatus.UNDER_REVIEW);
            paymentRepository.save(payment);
        });

        fraudFlagRepository.save(FraudFlag.builder()
                .flagId(UUID.randomUUID())
                .paymentId(event.paymentId())
                .riskLevel(finding.riskLevel())
                .reason(enriched.explanation())
                .actionTaken(finding.action())
                .createdAt(Instant.now())
                .build());

        kafkaEventPublisher.publish(
                topics.fraudAlerts(),
                event.clientId(),
                new FraudAlertEvent(
                        event.paymentId(), event.clientId(), finding.riskLevel(), finding.action(), enriched.explanation(), Instant.now()));

        kafkaEventPublisher.publish(
                topics.notifications(),
                event.clientId(),
                new NotificationEvent(
                        UUID.randomUUID(), event.paymentId(), event.clientId(), NotificationType.FRAUD_ALERT,
                        enriched.explanation(), Instant.now()));

        meterRegistry.counter("fraud.decision", "action", finding.action().name()).increment();
        log.warn("Payment flagged by fraud detection: paymentId={}, action={}, reason={}",
                event.paymentId(), finding.action(), enriched.explanation());
    }

    private void pass(PaymentEvent event) {
        meterRegistry.counter("fraud.decision", "action", FraudAction.PASS.name()).increment();
        kafkaEventPublisher.publish(
                topics.paymentsValidated(),
                event.clientId(),
                new PaymentValidatedEvent(event.paymentId(), event.clientId(), event.amount(), event.country(), event.createdAt()));
    }

    @Override
    @Transactional
    public void approve(UUID paymentId) {
        Payment payment = requireUnderReview(paymentId);

        kafkaEventPublisher.publish(
                topics.paymentsValidated(),
                payment.getClientId(),
                new PaymentValidatedEvent(
                        payment.getPaymentId(), payment.getClientId(), payment.getAmount(), payment.getCountry(), payment.getCreatedAt()));

        log.info("Payment approved by compliance review: paymentId={}", paymentId);
    }

    @Override
    @Transactional
    public void reject(UUID paymentId) {
        Payment payment = requireUnderReview(paymentId);
        payment.setStatus(PaymentStatus.BLOCKED);
        paymentRepository.save(payment);

        kafkaEventPublisher.publish(
                topics.notifications(),
                payment.getClientId(),
                new NotificationEvent(
                        UUID.randomUUID(), payment.getPaymentId(), payment.getClientId(), NotificationType.FRAUD_ALERT,
                        "Payment rejected by compliance review", Instant.now()));

        log.warn("Payment rejected by compliance review: paymentId={}", paymentId);
    }

    private Payment requireUnderReview(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() != PaymentStatus.UNDER_REVIEW) {
            throw new InvalidPaymentStateException(paymentId, PaymentStatus.UNDER_REVIEW, payment.getStatus());
        }
        return payment;
    }
}
