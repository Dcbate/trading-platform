package com.dcbate.tradingplatform.statement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.activity.repository.ActivityRepository;
import com.dcbate.tradingplatform.domain.Activity;
import com.dcbate.tradingplatform.domain.ActivityType;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.payment.repository.PaymentRepository;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.statement.api.dto.BankStatementEntry;
import com.dcbate.tradingplatform.statement.api.dto.BankStatementResponse;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class BankStatementServiceImplTest {

    private static final String CLIENT_ID = "client-1";

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ActivityRepository activityRepository;

    private BankStatementServiceImpl statementService;

    private final CallerPrincipal owner = new CallerPrincipal(CLIENT_ID, false);
    private final CallerPrincipal otherClient = new CallerPrincipal("client-2", false);

    @BeforeEach
    void setUp() {
        statementService = new BankStatementServiceImpl(orderRepository, paymentRepository, activityRepository);
        org.mockito.Mockito.lenient().when(orderRepository.findByClientIdOrderByCreatedAtDesc(CLIENT_ID)).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(paymentRepository.findByClientIdOrderByCreatedAtDesc(CLIENT_ID)).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(activityRepository.findByClientIdOrderByOccurredAtDesc(CLIENT_ID)).thenReturn(List.of());
    }

    @Test
    void rejectsALookupForSomeoneElsesStatement() {
        assertThatThrownBy(() -> statementService.getStatement(CLIENT_ID, null, otherClient))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void mergesOrdersPaymentsAndActivityIntoOneChronologicallySortedFeed() {
        Instant now = Instant.now();
        UUID accountId = UUID.randomUUID();

        Order order = Order.builder()
                .orderId(UUID.randomUUID()).clientId(CLIENT_ID).currencyPair("EUR/USD")
                .side(OrderSide.BUY).quantity(new BigDecimal("5")).price(new BigDecimal("1.0850"))
                .status(OrderStatus.FILLED).createdAt(now.minus(3, ChronoUnit.MINUTES))
                .build();
        when(orderRepository.findByClientIdOrderByCreatedAtDesc(CLIENT_ID)).thenReturn(List.of(order));

        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID()).clientId(CLIENT_ID).sourceAccountId(accountId)
                .amount(new BigDecimal("100")).status(PaymentStatus.SETTLED).idempotencyKey("k1")
                .country("DE").createdAt(now.minus(2, ChronoUnit.MINUTES))
                .build();
        when(paymentRepository.findByClientIdOrderByCreatedAtDesc(CLIENT_ID)).thenReturn(List.of(payment));

        Activity deposit = Activity.builder()
                .activityId(UUID.randomUUID()).clientId(CLIENT_ID).accountId(accountId)
                .type(ActivityType.DEPOSIT).amount(new BigDecimal("50")).currency("GBP")
                .description("Deposit into Current GBP").occurredAt(now.minus(1, ChronoUnit.MINUTES))
                .build();
        when(activityRepository.findByClientIdOrderByOccurredAtDesc(CLIENT_ID)).thenReturn(List.of(deposit));

        BankStatementResponse response = statementService.getStatement(CLIENT_ID, null, owner);

        assertThat(response.clientId()).isEqualTo(CLIENT_ID);
        assertThat(response.entries()).hasSize(3);
        assertThat(response.entries().stream().map(BankStatementEntry::type).toList()).containsExactly(
                ActivityType.DEPOSIT, ActivityType.PAYMENT, ActivityType.FX_ORDER);

        BankStatementEntry depositEntry = response.entries().get(0);
        assertThat(depositEntry.amount()).isEqualByComparingTo("50");
        assertThat(depositEntry.currency()).isEqualTo("GBP");
        assertThat(depositEntry.description()).isEqualTo("Deposit into Current GBP");

        BankStatementEntry paymentEntry = response.entries().get(1);
        assertThat(paymentEntry.amount()).isEqualByComparingTo("-100");
        assertThat(paymentEntry.currency()).isNull();

        BankStatementEntry orderEntry = response.entries().get(2);
        assertThat(orderEntry.amount()).isNull();
    }

    @Test
    void scopesToOneAccountWhenAccountIdIsProvided() {
        UUID accountId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        Instant now = Instant.now();

        Order matchingOrder = Order.builder()
                .orderId(UUID.randomUUID()).clientId(CLIENT_ID).accountId(accountId).currencyPair("TSLA")
                .side(OrderSide.BUY).quantity(BigDecimal.ONE).price(new BigDecimal("200"))
                .status(OrderStatus.FILLED).createdAt(now)
                .build();
        Order otherOrder = Order.builder()
                .orderId(UUID.randomUUID()).clientId(CLIENT_ID).accountId(otherAccountId).currencyPair("AAPL")
                .side(OrderSide.BUY).quantity(BigDecimal.ONE).price(new BigDecimal("100"))
                .status(OrderStatus.FILLED).createdAt(now)
                .build();
        when(orderRepository.findByClientIdOrderByCreatedAtDesc(CLIENT_ID)).thenReturn(List.of(matchingOrder, otherOrder));

        Activity matchingActivity = Activity.builder()
                .activityId(UUID.randomUUID()).clientId(CLIENT_ID).accountId(accountId)
                .type(ActivityType.DEPOSIT).amount(BigDecimal.TEN).currency("GBP")
                .description("Deposit").occurredAt(now)
                .build();
        when(activityRepository.findByClientIdAndAccountIdOrderByOccurredAtDesc(CLIENT_ID, accountId))
                .thenReturn(List.of(matchingActivity));

        BankStatementResponse response = statementService.getStatement(CLIENT_ID, accountId, owner);

        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries()).extracting(BankStatementEntry::reference)
                .containsExactlyInAnyOrder(matchingOrder.getOrderId(), matchingActivity.getActivityId());
    }
}
