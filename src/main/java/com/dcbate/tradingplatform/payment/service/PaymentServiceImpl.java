package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.exception.AccountNotActiveException;
import com.dcbate.tradingplatform.exception.AccountNotFoundException;
import com.dcbate.tradingplatform.exception.InsufficientFundsException;
import com.dcbate.tradingplatform.exception.PaymentNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.PaymentEvent;
import com.dcbate.tradingplatform.payment.api.dto.PaymentRequest;
import com.dcbate.tradingplatform.payment.api.dto.PaymentResponse;
import com.dcbate.tradingplatform.payment.repository.PaymentRepository;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @see PaymentService
 *
 * The account check here is a point-in-time balance check, not a fund reservation/hold — two
 * concurrent payments from the same account can both pass it before either settles. A real bank
 * would hold funds immediately; that's flagged as a follow-on (see README's known gaps), not
 * built here. {@code LedgerServiceImpl} has its own defensive re-check at settlement time as a
 * second line of defense against exactly that race.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;

    @Override
    @Transactional
    public PaymentResponse submitPayment(PaymentRequest request, CallerPrincipal caller) {
        caller.requireOwner(request.clientId());
        return paymentRepository.findByIdempotencyKey(request.idempotencyKey())
                .map(existing -> {
                    log.info("Idempotent replay for idempotencyKey={}, returning existing paymentId={}",
                            request.idempotencyKey(), existing.getPaymentId());
                    return PaymentResponse.from(existing);
                })
                .orElseGet(() -> acceptNewPayment(request));
    }

    private PaymentResponse acceptNewPayment(PaymentRequest request) {
        Account account = requireFundedAccount(request);

        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID())
                .clientId(request.clientId())
                .sourceAccountId(account.getAccountId())
                .amount(request.amount())
                .status(PaymentStatus.PENDING)
                .idempotencyKey(request.idempotencyKey())
                .country(request.country())
                .createdAt(Instant.now())
                .build();

        Payment saved = paymentRepository.save(payment);

        kafkaEventPublisher.publish(
                topics.payments(),
                saved.getClientId(),
                new PaymentEvent(saved.getPaymentId(), saved.getClientId(), saved.getAmount(), saved.getCountry(), saved.getCreatedAt()));

        log.info("Payment accepted: paymentId={}, clientId={}, sourceAccountId={}, amount={}",
                saved.getPaymentId(), saved.getClientId(), saved.getSourceAccountId(), saved.getAmount());

        return PaymentResponse.from(saved);
    }

    private Account requireFundedAccount(PaymentRequest request) {
        Account account = accountRepository.findById(request.sourceAccountId())
                .filter(a -> a.getClientId().equals(request.clientId()))
                .orElseThrow(() -> new AccountNotFoundException(request.sourceAccountId()));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(account.getAccountId());
        }
        if (account.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(account.getAccountId(), request.amount(), account.getBalance());
        }
        return account;
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId, CallerPrincipal caller) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        caller.requireOwner(payment.getClientId());
        return PaymentResponse.from(payment);
    }
}
