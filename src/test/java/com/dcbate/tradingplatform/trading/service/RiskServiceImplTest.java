package com.dcbate.tradingplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.ai.AnomalyContext;
import com.dcbate.tradingplatform.ai.AnomalyDetector;
import com.dcbate.tradingplatform.ai.AnomalyResult;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.config.RiskProperties;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.domain.RiskAlert;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.OrderEvent;
import com.dcbate.tradingplatform.kafka.event.OrderValidatedEvent;
import com.dcbate.tradingplatform.kafka.event.RiskAlertEvent;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import com.dcbate.tradingplatform.trading.repository.RiskAlertRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskServiceImplTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RiskAlertRepository riskAlertRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private AnomalyDetector anomalyDetector;

    private RiskServiceImpl riskService;

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "settlements", "fraud-alerts", "notifications", "notifications-dlq",
                "account-activity", "transfers", "loans");
        RiskProperties riskProperties = new RiskProperties(new BigDecimal("1000000"), 5, 60);
        riskService = new RiskServiceImpl(
                orderRepository, riskAlertRepository, kafkaEventPublisher, topics, riskProperties, new OrderVelocityTracker(), anomalyDetector);
    }

    private OrderEvent orderEvent(String clientId, String quantity, String price) {
        return new OrderEvent(UUID.randomUUID(), clientId, "AAPL", OrderSide.BUY, new BigDecimal(quantity), new BigDecimal(price), Instant.now());
    }

    @Test
    void withinLimitsValidatesTheOrder() {
        when(orderRepository.sumOpenNotionalByClientId(anyString(), any())).thenReturn(BigDecimal.ZERO);
        Order order = Order.builder().orderId(UUID.randomUUID()).status(OrderStatus.PENDING).build();
        OrderEvent event = orderEvent("client-1", "10", "100.00");
        when(orderRepository.findById(event.orderId())).thenReturn(Optional.of(order));

        riskService.evaluate(event);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.VALIDATED);
        verify(kafkaEventPublisher).publish(eq("orders-validated"), eq("AAPL"), any(OrderValidatedEvent.class));
        verify(riskAlertRepository, never()).save(any());
    }

    @Test
    void exceedingNotionalLimitRejectsTheOrder() {
        when(orderRepository.sumOpenNotionalByClientId(anyString(), any())).thenReturn(new BigDecimal("999999"));
        when(anomalyDetector.explain(any())).thenAnswer(inv -> new AnomalyResult(inv.getArgument(0, AnomalyContext.class).description(), false));
        Order order = Order.builder().orderId(UUID.randomUUID()).status(OrderStatus.PENDING).build();
        OrderEvent event = orderEvent("client-1", "10", "100.00");
        when(orderRepository.findById(event.orderId())).thenReturn(Optional.of(order));

        riskService.evaluate(event);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        verify(riskAlertRepository).save(any(RiskAlert.class));
        verify(kafkaEventPublisher).publish(eq("risk-alerts"), eq("client-1"), any(RiskAlertEvent.class));
    }

    @Test
    void exceedingVelocityLimitRejectsTheOrder() {
        when(orderRepository.sumOpenNotionalByClientId(anyString(), any())).thenReturn(BigDecimal.ZERO);
        when(anomalyDetector.explain(any())).thenAnswer(inv -> new AnomalyResult(inv.getArgument(0, AnomalyContext.class).description(), false));
        when(orderRepository.findById(any())).thenReturn(Optional.of(Order.builder().status(OrderStatus.PENDING).build()));

        for (int i = 0; i < 5; i++) {
            riskService.evaluate(orderEvent("client-2", "1", "10.00"));
        }
        OrderEvent sixth = orderEvent("client-2", "1", "10.00");
        riskService.evaluate(sixth);

        ArgumentCaptor<RiskAlertEvent> captor = ArgumentCaptor.forClass(RiskAlertEvent.class);
        verify(kafkaEventPublisher, times(1)).publish(eq("risk-alerts"), eq("client-2"), captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(sixth.orderId());
    }
}
