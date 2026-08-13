package com.dcbate.tradingplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.exception.OrderNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.OrderEvent;
import com.dcbate.tradingplatform.trading.api.dto.OrderRequest;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "settlements", "fraud-alerts", "notifications", "notifications-dlq");
        orderService = new OrderServiceImpl(orderRepository, kafkaEventPublisher, topics);
    }

    private OrderRequest request() {
        return new OrderRequest("client-1", "AAPL", OrderSide.BUY, new BigDecimal("10"), new BigDecimal("150.00"));
    }

    @Test
    void submitOrderPersistsAsPendingAndPublishesEvent() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.submitOrder(request());

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.symbol()).isEqualTo("AAPL");
        verify(kafkaEventPublisher).publish(eq("orders"), eq("AAPL"), any(OrderEvent.class));
    }

    @Test
    void submitOrderPublishesEventMatchingSavedOrder() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);

        OrderResponse response = orderService.submitOrder(request());

        verify(kafkaEventPublisher).publish(eq("orders"), eq("AAPL"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().orderId()).isEqualTo(response.orderId());
    }

    @Test
    void getOrderReturnsMappedResponseWhenFound() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder().orderId(orderId).clientId("client-1").symbol("AAPL")
                .side(OrderSide.BUY).quantity(new BigDecimal("10")).price(new BigDecimal("150.00"))
                .status(OrderStatus.FILLED).build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrder(orderId);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.status()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void getOrderThrowsWhenNotFound() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(orderId)).isInstanceOf(OrderNotFoundException.class);
    }
}
