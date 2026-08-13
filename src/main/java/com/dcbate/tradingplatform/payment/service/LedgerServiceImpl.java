package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.LedgerEntry;
import com.dcbate.tradingplatform.domain.LedgerEntryType;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.LedgerEntryEvent;
import com.dcbate.tradingplatform.payment.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerServiceImpl implements LedgerService {

    private static final String PLATFORM_ACCOUNT = "platform:settlement";

    private final LedgerEntryRepository ledgerEntryRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;

    @Override
    @Transactional
    public void recordDoubleEntry(Payment payment) {
        writeEntry(payment, clientAccountId(payment.getClientId()), LedgerEntryType.DEBIT, payment.getAmount());
        writeEntry(payment, PLATFORM_ACCOUNT, LedgerEntryType.CREDIT, payment.getAmount());
    }

    @Override
    @Transactional
    public void reverseEntries(Payment payment) {
        List<LedgerEntry> originals = ledgerEntryRepository.findByPaymentId(payment.getPaymentId());
        for (LedgerEntry original : originals) {
            LedgerEntryType reversedType =
                    original.getEntryType() == LedgerEntryType.DEBIT ? LedgerEntryType.CREDIT : LedgerEntryType.DEBIT;
            writeEntry(payment, original.getAccountId(), reversedType, original.getAmount());
        }
    }

    private void writeEntry(Payment payment, String accountId, LedgerEntryType type, BigDecimal amount) {
        LedgerEntry entry = LedgerEntry.builder()
                .entryId(UUID.randomUUID())
                .paymentId(payment.getPaymentId())
                .accountId(accountId)
                .entryType(type)
                .amount(amount)
                .createdAt(Instant.now())
                .build();

        LedgerEntry saved = ledgerEntryRepository.save(entry);

        kafkaEventPublisher.publish(
                topics.ledgerEntries(),
                payment.getClientId(),
                new LedgerEntryEvent(
                        saved.getEntryId(), saved.getPaymentId(), saved.getAccountId(), saved.getEntryType(),
                        saved.getAmount(), saved.getCreatedAt()));
    }

    private String clientAccountId(String clientId) {
        return "client:" + clientId;
    }
}
