package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.NotificationType;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.domain.Settlement;
import com.dcbate.tradingplatform.domain.SettlementStatus;
import com.dcbate.tradingplatform.exception.AccountNotFoundException;
import com.dcbate.tradingplatform.exception.InsufficientFundsException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.NotificationEvent;
import com.dcbate.tradingplatform.kafka.event.PaymentValidatedEvent;
import com.dcbate.tradingplatform.payment.repository.PaymentRepository;
import com.dcbate.tradingplatform.payment.repository.SettlementRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga orchestrator (not choreographed via extra Kafka hops): reserve -> ledger -> clear ->
 * compensate. Each step is a direct call within one transaction, which keeps the compensation
 * logic in one reviewable place instead of spread across multiple consumers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementServiceImpl implements SettlementService {

    private final PaymentRepository paymentRepository;
    private final SettlementRepository settlementRepository;
    private final LedgerService ledgerService;
    private final BankClearingClient bankClearingClient;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;

    @Override
    @Transactional
    public void process(PaymentValidatedEvent event) {
        Payment payment = paymentRepository.findById(event.paymentId()).orElse(null);
        if (payment == null) {
            log.error("Cannot settle unknown paymentId={}", event.paymentId());
            return;
        }

        payment.setStatus(PaymentStatus.RESERVED);
        paymentRepository.save(payment);

        Settlement settlement = settlementRepository.save(Settlement.builder()
                .settlementId(UUID.randomUUID())
                .paymentId(payment.getPaymentId())
                .status(SettlementStatus.IN_PROGRESS)
                .attemptCount(1)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        try {
            ledgerService.recordDoubleEntry(payment);
        } catch (InsufficientFundsException | AccountNotFoundException e) {
            // Nothing was booked (the balance check runs before any entry is written), so there's
            // nothing for reverseEntries to undo — this is a straight rejection, not a compensation.
            failWithoutCompensation(payment, settlement, e.getMessage());
            return;
        }

        if (bankClearingClient.clear(payment)) {
            complete(payment, settlement);
        } else {
            compensate(payment, settlement);
        }
    }

    private void failWithoutCompensation(Payment payment, Settlement settlement, String reason) {
        settlement.setStatus(SettlementStatus.COMPENSATED);
        settlement.setUpdatedAt(Instant.now());
        settlementRepository.save(settlement);

        payment.setStatus(PaymentStatus.FAILED);
        paymentRepository.save(payment);

        publishNotification(payment, NotificationType.PAYMENT_FAILED, reason);
        log.warn("Payment settlement rejected before booking: paymentId={}, reason={}", payment.getPaymentId(), reason);
    }

    private void complete(Payment payment, Settlement settlement) {
        settlement.setStatus(SettlementStatus.CLEARED);
        settlement.setUpdatedAt(Instant.now());
        settlementRepository.save(settlement);

        payment.setStatus(PaymentStatus.SETTLED);
        paymentRepository.save(payment);

        publishNotification(payment, NotificationType.PAYMENT_SETTLED, "Payment settled successfully");
        log.info("Payment settled: paymentId={}", payment.getPaymentId());
    }

    private void compensate(Payment payment, Settlement settlement) {
        ledgerService.reverseEntries(payment);

        settlement.setStatus(SettlementStatus.COMPENSATED);
        settlement.setUpdatedAt(Instant.now());
        settlementRepository.save(settlement);

        payment.setStatus(PaymentStatus.FAILED);
        paymentRepository.save(payment);

        String reason = "Bank clearing failed for amount %s; ledger entries reversed".formatted(payment.getAmount());
        publishNotification(payment, NotificationType.PAYMENT_FAILED, reason);
        log.warn("Payment settlement failed and compensated: paymentId={}", payment.getPaymentId());
    }

    private void publishNotification(Payment payment, NotificationType type, String reason) {
        kafkaEventPublisher.publish(
                topics.notifications(),
                payment.getClientId(),
                new NotificationEvent(UUID.randomUUID(), payment.getPaymentId(), payment.getClientId(), type, reason, Instant.now()));
    }
}
