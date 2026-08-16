package com.dcbate.tradingplatform.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.AccountType;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.exception.PaymentNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.PaymentEvent;
import com.dcbate.tradingplatform.payment.api.dto.PaymentRequest;
import com.dcbate.tradingplatform.payment.api.dto.PaymentResponse;
import com.dcbate.tradingplatform.payment.repository.PaymentRepository;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    private PaymentServiceImpl paymentService;

    private final UUID sourceAccountId = UUID.randomUUID();
    private final CallerPrincipal owner = new CallerPrincipal("client-1", false);
    private final CallerPrincipal otherClient = new CallerPrincipal("client-2", false);

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "fraud-alerts", "notifications",
                "account-activity", "transfers", "loans");
        paymentService = new PaymentServiceImpl(paymentRepository, accountRepository, kafkaEventPublisher, topics);
    }

    private Account fundedAccount() {
        return Account.builder()
                .accountId(sourceAccountId).clientId("client-1").accountType(AccountType.CHECKING)
                .currency("USD").balance(new BigDecimal("1000.00")).status(AccountStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
    }

    private PaymentRequest request(String idempotencyKey) {
        return new PaymentRequest("client-1", sourceAccountId, new BigDecimal("100.00"), idempotencyKey, "US");
    }

    @Test
    void submitPaymentPersistsAsPendingAndPublishesEvent() {
        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(accountRepository.findById(sourceAccountId)).thenReturn(Optional.of(fundedAccount()));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.submitPayment(request("key-1"), owner);

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        verify(kafkaEventPublisher).publish(eq("payments"), eq("client-1"), any(PaymentEvent.class));
    }

    @Test
    void submitPaymentDeniedForAnotherClient() {
        assertThatThrownBy(() -> paymentService.submitPayment(request("key-2"), otherClient))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void submitPaymentIsIdempotentOnRepeatedKey() {
        Payment existing = Payment.builder()
                .paymentId(UUID.randomUUID()).clientId("client-1").amount(new BigDecimal("100.00"))
                .status(PaymentStatus.SETTLED).idempotencyKey("key-1").country("US").createdAt(Instant.now())
                .build();
        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        PaymentResponse response = paymentService.submitPayment(request("key-1"), owner);

        assertThat(response.paymentId()).isEqualTo(existing.getPaymentId());
        assertThat(response.status()).isEqualTo(PaymentStatus.SETTLED);
        verify(paymentRepository, never()).save(any());
        verify(kafkaEventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void getPaymentThrowsWhenNotFound() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPayment(paymentId, owner)).isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void getPaymentDeniedForNonOwner() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .paymentId(paymentId).clientId("client-1").sourceAccountId(sourceAccountId).amount(new BigDecimal("100.00"))
                .status(PaymentStatus.SETTLED).idempotencyKey("key-3").country("US").createdAt(Instant.now())
                .build();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.getPayment(paymentId, otherClient)).isInstanceOf(AccessDeniedException.class);
    }
}
