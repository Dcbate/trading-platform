package com.dcbate.tradingplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.OrderValidatedEvent;
import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import com.dcbate.tradingplatform.trading.repository.TradeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatchingEngineServiceImplTest {

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private TradeRepository tradeRepository;

    private MatchingEngineServiceImpl matchingEngineService;

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "fraud-alerts", "notifications",
                "account-activity", "transfers", "loans");
        matchingEngineService = new MatchingEngineServiceImpl(kafkaEventPublisher, topics, orderRepository, tradeRepository);
    }

    private OrderValidatedEvent order(OrderSide side, String price, String quantity) {
        return new OrderValidatedEvent(UUID.randomUUID(), "client-1", "EUR/USD", side, new BigDecimal(quantity), new BigDecimal(price), Instant.now());
    }

    @Test
    void restingOrderProducesNoTradeAndIncreasesDepth() {
        List<TradeEvent> trades = matchingEngineService.match(order(OrderSide.BUY, "150.00", "10"));

        assertThat(trades).isEmpty();
        assertThat(matchingEngineService.orderBookDepth()).isEqualTo(1);
    }

    @Test
    void crossingOrdersProduceAFilledTradeAndPublishIt() {
        matchingEngineService.match(order(OrderSide.SELL, "150.00", "10"));

        List<TradeEvent> trades = matchingEngineService.match(order(OrderSide.BUY, "150.00", "10"));

        assertThat(trades).hasSize(1);
        TradeEvent trade = trades.get(0);
        assertThat(trade.buyOrderStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(trade.sellOrderStatus()).isEqualTo(OrderStatus.FILLED);
        verify(kafkaEventPublisher).publish(eq("trades"), eq("EUR/USD"), any(TradeEvent.class));
    }

    @Test
    void partialFillLeavesResidualStatusOnTheLargerOrder() {
        matchingEngineService.match(order(OrderSide.SELL, "150.00", "4"));

        List<TradeEvent> trades = matchingEngineService.match(order(OrderSide.BUY, "150.00", "10"));

        assertThat(trades.get(0).sellOrderStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(trades.get(0).buyOrderStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(matchingEngineService.orderBookDepth()).isEqualTo(1);
    }

    // --- recoverRestingOrders(): the fix for orders that outlive an app restart ---

    private Order persistedOrder(OrderStatus status, OrderSide side, String price, String quantity, Instant createdAt) {
        return Order.builder()
                .orderId(UUID.randomUUID())
                .clientId("client-1")
                .currencyPair("EUR/USD")
                .side(side)
                .price(new BigDecimal(price))
                .quantity(new BigDecimal(quantity))
                .status(status)
                .createdAt(createdAt)
                .build();
    }

    @Test
    void recoveryReloadsAValidatedOrderAtItsFullQuantity() {
        Order resting = persistedOrder(OrderStatus.VALIDATED, OrderSide.BUY, "150.00", "10", Instant.now());
        when(orderRepository.findByStatusInOrderByCreatedAtAsc(ArgumentMatchers.anyList())).thenReturn(List.of(resting));

        matchingEngineService.recoverRestingOrders();

        assertThat(matchingEngineService.orderBookDepth()).isEqualTo(1);

        // Prove it actually participates in matching, not just a depth-counter artifact.
        List<TradeEvent> trades = matchingEngineService.match(order(OrderSide.SELL, "150.00", "10"));
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).buyOrderId()).isEqualTo(resting.getOrderId());
        assertThat(trades.get(0).quantity()).isEqualByComparingTo("10");
    }

    @Test
    void recoveryUsesRemainingQuantityNotOriginalQuantityForAPartiallyFilledOrder() {
        Order resting = persistedOrder(OrderStatus.PARTIALLY_FILLED, OrderSide.BUY, "150.00", "10", Instant.now());
        when(orderRepository.findByStatusInOrderByCreatedAtAsc(ArgumentMatchers.anyList())).thenReturn(List.of(resting));
        // 4 of the original 10 already filled before the restart — only 6 should come back.
        when(tradeRepository.sumQuantityByOrderId(resting.getOrderId())).thenReturn(new BigDecimal("4"));

        matchingEngineService.recoverRestingOrders();

        // A sell for exactly the remaining 6 should fully clear it, not leave a residual —
        // if the bug reintroduced the full original 10, this would come back PARTIALLY_FILLED.
        List<TradeEvent> trades = matchingEngineService.match(order(OrderSide.SELL, "150.00", "6"));
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).buyOrderStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(matchingEngineService.orderBookDepth()).isZero();
    }

    @Test
    void recoveryIsANoOpWhenNothingWasResting() {
        when(orderRepository.findByStatusInOrderByCreatedAtAsc(ArgumentMatchers.anyList())).thenReturn(List.of());

        matchingEngineService.recoverRestingOrders();

        assertThat(matchingEngineService.orderBookDepth()).isZero();
    }
}
