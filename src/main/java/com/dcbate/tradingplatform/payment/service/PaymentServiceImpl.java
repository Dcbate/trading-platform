package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.exception.PaymentNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.PaymentEvent;
import com.dcbate.tradingplatform.payment.api.dto.PaymentRequest;
import com.dcbate.tradingplatform.payment.api.dto.PaymentResponse;
import com.dcbate.tradingplatform.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;

    @Override
    @Transactional
    public PaymentResponse submitPayment(PaymentRequest request) {
        return paymentRepository.findByIdempotencyKey(request.idempotencyKey())
                .map(existing -> {
                    log.info("Idempotent replay for idempotencyKey={}, returning existing paymentId={}",
                            request.idempotencyKey(), existing.getPaymentId());
                    return PaymentResponse.from(existing);
                })
                .orElseGet(() -> acceptNewPayment(request));
    }

    private PaymentResponse acceptNewPayment(PaymentRequest request) {
        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID())
                .clientId(request.clientId())
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

        log.info("Payment accepted: paymentId={}, clientId={}, amount={}", saved.getPaymentId(), saved.getClientId(), saved.getAmount());

        return PaymentResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
