package com.dcbate.tradingplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.OrderValidatedEvent;
import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatchingEngineServiceImplTest {

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    private MatchingEngineServiceImpl matchingEngineService;

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "fraud-alerts", "notifications",
                "account-activity", "transfers", "loans");
        matchingEngineService = new MatchingEngineServiceImpl(kafkaEventPublisher, topics);
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
}
