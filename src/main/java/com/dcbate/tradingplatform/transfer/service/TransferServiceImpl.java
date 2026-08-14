package com.dcbate.tradingplatform.transfer.service;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.Transfer;
import com.dcbate.tradingplatform.domain.TransferStatus;
import com.dcbate.tradingplatform.exception.AccountNotActiveException;
import com.dcbate.tradingplatform.exception.AccountNotFoundException;
import com.dcbate.tradingplatform.exception.CurrencyMismatchException;
import com.dcbate.tradingplatform.exception.InsufficientFundsException;
import com.dcbate.tradingplatform.exception.TransferNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.TransferEvent;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.transfer.api.dto.TransferRequest;
import com.dcbate.tradingplatform.transfer.api.dto.TransferResponse;
import com.dcbate.tradingplatform.transfer.repository.TransferRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** @see TransferService */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public TransferResponse transfer(TransferRequest request, CallerPrincipal caller) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return doTransfer(request, caller);
        } finally {
            sample.stop(Timer.builder("transfer.latency").publishPercentileHistogram().register(meterRegistry));
        }
    }

    private TransferResponse doTransfer(TransferRequest request, CallerPrincipal caller) {
        Account from = requireActiveAccount(request.fromAccountId());
        caller.requireOwner(from.getClientId());
        Account to = requireActiveAccount(request.toAccountId());

        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new CurrencyMismatchException(from.getCurrency(), to.getCurrency());
        }
        if (from.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(from.getAccountId(), request.amount(), from.getBalance());
        }

        from.setBalance(from.getBalance().subtract(request.amount()));
        to.setBalance(to.getBalance().add(request.amount()));
        accountRepository.save(from);
        accountRepository.save(to);

        Transfer transfer = transferRepository.save(Transfer.builder()
                .transferId(UUID.randomUUID())
                .fromAccountId(from.getAccountId())
                .toAccountId(to.getAccountId())
                .fromClientId(from.getClientId())
                .toClientId(to.getClientId())
                .amount(request.amount())
                .status(TransferStatus.COMPLETED)
                .createdAt(Instant.now())
                .build());

        kafkaEventPublisher.publish(topics.transfers(), transfer.getFromClientId(), new TransferEvent(
                transfer.getTransferId(), transfer.getFromAccountId(), transfer.getToAccountId(),
                transfer.getFromClientId(), transfer.getToClientId(), transfer.getAmount(),
                transfer.getStatus(), transfer.getCreatedAt()));

        log.info("Transfer completed: transferId={}, fromAccountId={}, toAccountId={}, amount={}",
                transfer.getTransferId(), transfer.getFromAccountId(), transfer.getToAccountId(), transfer.getAmount());

        return TransferResponse.from(transfer);
    }

    @Override
    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID transferId, CallerPrincipal caller) {
        Transfer transfer = transferRepository.findById(transferId).orElseThrow(() -> new TransferNotFoundException(transferId));
        if (!caller.staff() && !caller.clientId().equals(transfer.getFromClientId()) && !caller.clientId().equals(transfer.getToClientId())) {
            throw new org.springframework.security.access.AccessDeniedException("Not a party to this transfer");
        }
        return TransferResponse.from(transfer);
    }

    private Account requireActiveAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(accountId);
        }
        return account;
    }
}
