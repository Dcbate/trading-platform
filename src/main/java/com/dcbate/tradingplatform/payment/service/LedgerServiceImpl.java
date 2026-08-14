package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.LedgerEntry;
import com.dcbate.tradingplatform.domain.LedgerEntryType;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.exception.AccountNotFoundException;
import com.dcbate.tradingplatform.exception.InsufficientFundsException;
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

/**
 * @see LedgerService
 *
 * The DEBIT side always references a real {@code Account} — its balance is mutated here, not
 * just journaled — while the CREDIT side goes to a symbolic platform clearing account (no
 * customer-facing {@code Account} row backs it, same as a bank's internal GL accounts).
 * {@code recordDoubleEntry} re-checks the balance defensively: {@code PaymentServiceImpl}'s check
 * at submission time is a point-in-time check, not a hold, so two concurrent payments from the
 * same account could both pass it before either reaches here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerServiceImpl implements LedgerService {

    private static final String PLATFORM_ACCOUNT = "platform:settlement";

    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountRepository accountRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;

    @Override
    @Transactional
    public void recordDoubleEntry(Payment payment) {
        Account account = accountRepository.findById(payment.getSourceAccountId())
                .orElseThrow(() -> new AccountNotFoundException(payment.getSourceAccountId()));
        if (account.getBalance().compareTo(payment.getAmount()) < 0) {
            throw new InsufficientFundsException(account.getAccountId(), payment.getAmount(), account.getBalance());
        }

        account.setBalance(account.getBalance().subtract(payment.getAmount()));
        accountRepository.save(account);

        writeEntry(payment, account.getAccountId().toString(), LedgerEntryType.DEBIT, payment.getAmount());
        writeEntry(payment, PLATFORM_ACCOUNT, LedgerEntryType.CREDIT, payment.getAmount());
    }

    @Override
    @Transactional
    public void reverseEntries(Payment payment) {
        accountRepository.findById(payment.getSourceAccountId()).ifPresent(account -> {
            account.setBalance(account.getBalance().add(payment.getAmount()));
            accountRepository.save(account);
        });

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
}
