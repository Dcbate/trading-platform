package com.dcbate.tradingplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.config.TradingProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.AccountType;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.domain.Position;
import com.dcbate.tradingplatform.exception.InsufficientFundsException;
import com.dcbate.tradingplatform.exception.InsufficientPositionException;
import com.dcbate.tradingplatform.exception.InvalidOrderQuantityException;
import com.dcbate.tradingplatform.exception.OrderNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.OrderEvent;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.trading.api.dto.OrderRequest;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import com.dcbate.tradingplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    private OrderServiceImpl orderService;

    private final CallerPrincipal owner = new CallerPrincipal("client-1", false);
    private final CallerPrincipal otherClient = new CallerPrincipal("client-2", false);

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "fraud-alerts", "notifications",
                "account-activity", "transfers", "loans");
        TradingProperties tradingProperties = new TradingProperties(
                java.util.List.of("EUR/USD"), java.util.List.of("AAPL"), java.util.List.of("BTC/USD"),
                new TradingProperties.PriceFeed(2000, new BigDecimal("10")));
        orderService = new OrderServiceImpl(orderRepository, accountRepository, positionRepository, kafkaEventPublisher, topics, tradingProperties);
    }

    private OrderRequest fxRequest() {
        return new OrderRequest("client-1", "EUR/USD", OrderSide.BUY, new BigDecimal("10"), new BigDecimal("150.00"), null);
    }

    private Account brokerageAccount(UUID accountId, BigDecimal balance) {
        return Account.builder().accountId(accountId).clientId("client-1").accountType(AccountType.BROKERAGE)
                .currency("USD").balance(balance).status(AccountStatus.ACTIVE).createdAt(Instant.now()).build();
    }

    @Test
    void submitOrderPersistsAsPendingAndPublishesEvent() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.submitOrder(fxRequest(), owner);

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.currencyPair()).isEqualTo("EUR/USD");
        verify(kafkaEventPublisher).publish(eq("orders"), eq("EUR/USD"), any(OrderEvent.class));
    }

    @Test
    void submitOrderPublishesEventMatchingSavedOrder() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);

        OrderResponse response = orderService.submitOrder(fxRequest(), owner);

        verify(kafkaEventPublisher).publish(eq("orders"), eq("EUR/USD"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().orderId()).isEqualTo(response.orderId());
    }

    @Test
    void submitOrderDeniedForAnotherClient() {
        assertThatThrownBy(() -> orderService.submitOrder(fxRequest(), otherClient)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void submitBuyOrderWithFundingAccountChecksSufficientCash() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(brokerageAccount(accountId, new BigDecimal("100.00"))));
        OrderRequest request = new OrderRequest("client-1", "AAPL", OrderSide.BUY, new BigDecimal("10"), new BigDecimal("190.00"), accountId);

        assertThatThrownBy(() -> orderService.submitOrder(request, owner)).isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void submitBuyOrderWithSufficientCashSucceeds() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(brokerageAccount(accountId, new BigDecimal("5000.00"))));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        OrderRequest request = new OrderRequest("client-1", "AAPL", OrderSide.BUY, new BigDecimal("10"), new BigDecimal("190.00"), accountId);

        OrderResponse response = orderService.submitOrder(request, owner);

        assertThat(response.accountId()).isEqualTo(accountId);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void submitStockOrderWithFractionalSharesThrows() {
        OrderRequest request = new OrderRequest("client-1", "AAPL", OrderSide.BUY, new BigDecimal("2.5"), new BigDecimal("190.00"), null);

        assertThatThrownBy(() -> orderService.submitOrder(request, owner)).isInstanceOf(InvalidOrderQuantityException.class);
    }

    @Test
    void submitFxOrderWithFractionalQuantitySucceeds() {
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        OrderRequest request = new OrderRequest("client-1", "EUR/USD", OrderSide.BUY, new BigDecimal("10.50"), new BigDecimal("1.08"), null);

        OrderResponse response = orderService.submitOrder(request, owner);

        assertThat(response.quantity()).isEqualByComparingTo("10.50");
    }

    @Test
    void submitCryptoOrderWithFractionalQuantitySucceeds() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(brokerageAccount(accountId, new BigDecimal("5000.00"))));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        // Crypto isn't in stockSymbols, so the whole-unit check doesn't apply — 0.001 BTC is a
        // perfectly ordinary crypto order, unlike 0.001 of a share.
        OrderRequest request = new OrderRequest("client-1", "BTC/USD", OrderSide.BUY, new BigDecimal("0.001"), new BigDecimal("65000.00"), accountId);

        OrderResponse response = orderService.submitOrder(request, owner);

        assertThat(response.quantity()).isEqualByComparingTo("0.001");
        assertThat(response.accountId()).isEqualTo(accountId);
    }

    @Test
    void submitSellOrderWithoutEnoughSharesThrows() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(brokerageAccount(accountId, BigDecimal.ZERO)));
        when(positionRepository.findByAccountIdAndSymbol(accountId, "AAPL")).thenReturn(Optional.of(
                Position.builder().positionId(UUID.randomUUID()).accountId(accountId).clientId("client-1")
                        .symbol("AAPL").quantity(new BigDecimal("5")).avgCost(new BigDecimal("190.00")).build()));
        OrderRequest request = new OrderRequest("client-1", "AAPL", OrderSide.SELL, new BigDecimal("10"), new BigDecimal("190.00"), accountId);

        assertThatThrownBy(() -> orderService.submitOrder(request, owner)).isInstanceOf(InsufficientPositionException.class);
    }

    @Test
    void getOrderReturnsMappedResponseWhenFound() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder().orderId(orderId).clientId("client-1").currencyPair("EUR/USD")
                .side(OrderSide.BUY).quantity(new BigDecimal("10")).price(new BigDecimal("150.00"))
                .status(OrderStatus.FILLED).build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrder(orderId, owner);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.status()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void getOrderThrowsWhenNotFound() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(orderId, owner)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void getOrderDeniedForAnotherClient() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder().orderId(orderId).clientId("client-1").status(OrderStatus.PENDING).build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.getOrder(orderId, otherClient)).isInstanceOf(AccessDeniedException.class);
    }
}
