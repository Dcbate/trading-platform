package com.dcbate.tradingplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.chronicle.TradeJournalWriter;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.domain.Trade;
import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import com.dcbate.tradingplatform.trading.repository.TradeRepository;
import com.dcbate.tradingplatform.trading.websocket.OrderStreamHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionServiceImplTest {

    @Mock
    private TradeRepository tradeRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private TradeJournalWriter tradeJournalWriter;

    @Mock
    private OrderStreamHandler orderStreamHandler;

    private ExecutionServiceImpl executionService;

    @BeforeEach
    void setUp() {
        executionService = new ExecutionServiceImpl(tradeRepository, orderRepository, tradeJournalWriter, orderStreamHandler);
    }

    @Test
    void recordTradePersistsTradeAndJournalsIt() {
        TradeEvent event = tradeEvent(OrderStatus.FILLED, OrderStatus.FILLED);
        when(orderRepository.findById(any())).thenReturn(Optional.empty());

        executionService.recordTrade(event);

        verify(tradeRepository).save(any(Trade.class));
        verify(tradeJournalWriter).append(event);
    }

    @Test
    void fullyFilledOrderGetsFilledAtTimestamp() {
        Order buyOrder = Order.builder().orderId(UUID.randomUUID()).status(OrderStatus.VALIDATED).build();
        TradeEvent event = tradeEvent(OrderStatus.FILLED, OrderStatus.PARTIALLY_FILLED);
        when(orderRepository.findById(event.buyOrderId())).thenReturn(Optional.of(buyOrder));
        when(orderRepository.findById(event.sellOrderId())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        executionService.recordTrade(event);

        assertThat(buyOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(buyOrder.getFilledAt()).isNotNull();
        verify(orderStreamHandler, times(1)).publish(any(OrderResponse.class));
    }

    private TradeEvent tradeEvent(OrderStatus buyStatus, OrderStatus sellStatus) {
        return new TradeEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "EUR/USD",
                new BigDecimal("10"), new BigDecimal("150.00"), buyStatus, sellStatus, Instant.now());
    }
}
