package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.config.ReconciliationProperties;
import com.dcbate.tradingplatform.domain.LedgerEntry;
import com.dcbate.tradingplatform.domain.LedgerEntryType;
import com.dcbate.tradingplatform.domain.NotificationType;
import com.dcbate.tradingplatform.domain.Payment;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * No real bank statement feed exists for Phase 2, so this checks internal ledger integrity
 * instead — for each cleared settlement, do its debit and credit entries actually net to zero.
 * That's a real, testable check; comparing against an actual bank statement is a Phase 3 item.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationServiceImpl implements ReconciliationService {

    private final SettlementRepository settlementRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ReconciliationAlertRepository reconciliationAlertRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;
    private final ReconciliationProperties reconciliationProperties;

    @Override
    @Transactional
    public void reconcile() {
        List<Settlement> clearedSettlements = settlementRepository.findByStatus(SettlementStatus.CLEARED);
        clearedSettlements.forEach(this::reconcileSettlement);
        log.info("Reconciliation run complete: checked {} cleared settlements", clearedSettlements.size());
    }

    private void reconcileSettlement(Settlement settlement) {
        UUID paymentId = settlement.getPaymentId();
        if (reconciliationAlertRepository.existsByPaymentId(paymentId)) {
            return;
        }

        List<LedgerEntry> entries = ledgerEntryRepository.findByPaymentId(paymentId);
        BigDecimal debits = sumByType(entries, LedgerEntryType.DEBIT);
        BigDecimal credits = sumByType(entries, LedgerEntryType.CREDIT);
        BigDecimal discrepancy = debits.subtract(credits).abs();

        if (discrepancy.compareTo(reconciliationProperties.autoResolveThreshold()) <= 0) {
            if (discrepancy.signum() > 0) {
                log.info("Reconciliation auto-resolved paymentId={}: discrepancy={} within threshold", paymentId, discrepancy);
            }
            return;
        }

        persistAlertAndNotify(paymentId, debits, credits, discrepancy);
    }

    private BigDecimal sumByType(List<LedgerEntry> entries, LedgerEntryType type) {
        return entries.stream()
                .filter(entry -> entry.getEntryType() == type)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void persistAlertAndNotify(UUID paymentId, BigDecimal expected, BigDecimal actual, BigDecimal discrepancy) {
        reconciliationAlertRepository.save(ReconciliationAlert.builder()
                .alertId(UUID.randomUUID())
                .paymentId(paymentId)
                .expectedAmount(expected)
                .actualAmount(actual)
                .discrepancy(discrepancy)
                .createdAt(Instant.now())
                .build());

        String clientId = paymentRepository.findById(paymentId).map(Payment::getClientId).orElse("unknown");

        kafkaEventPublisher.publish(
                topics.notifications(),
                clientId,
                new NotificationEvent(
                        UUID.randomUUID(), paymentId, clientId, NotificationType.RECONCILIATION_ALERT,
                        "Ledger discrepancy of %s detected for payment %s (debits=%s, credits=%s)"
                                .formatted(discrepancy, paymentId, expected, actual),
                        Instant.now()));

        log.error("Reconciliation discrepancy detected: paymentId={}, discrepancy={}", paymentId, discrepancy);
    }
}
