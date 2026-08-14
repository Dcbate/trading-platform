package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.ai.AnomalyContext;
import com.dcbate.tradingplatform.ai.AnomalyDetector;
import com.dcbate.tradingplatform.ai.AnomalyResult;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.config.RiskProperties;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.domain.RiskAlert;
import com.dcbate.tradingplatform.domain.RiskLevel;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.OrderEvent;
import com.dcbate.tradingplatform.kafka.event.OrderValidatedEvent;
import com.dcbate.tradingplatform.kafka.event.RiskAlertEvent;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import com.dcbate.tradingplatform.trading.repository.RiskAlertRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** @see RiskService */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskServiceImpl implements RiskService {

    private static final List<OrderStatus> OPEN_STATUSES =
            List.of(OrderStatus.PENDING, OrderStatus.VALIDATED, OrderStatus.PARTIALLY_FILLED);

    private final OrderRepository orderRepository;
    private final RiskAlertRepository riskAlertRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;
    private final RiskProperties riskProperties;
    private final OrderVelocityTracker velocityTracker;
    private final AnomalyDetector anomalyDetector;

    @Override
    @Transactional
    public void evaluate(OrderEvent event) {
        Optional<String> rejectionReason = checkNotionalLimit(event).or(() -> checkVelocityLimit(event));
        rejectionReason.ifPresentOrElse(reason -> reject(event, reason), () -> validate(event));
    }

    private Optional<String> checkNotionalLimit(OrderEvent event) {
        BigDecimal openNotional = orderRepository.sumOpenNotionalByClientId(event.clientId(), OPEN_STATUSES);
        BigDecimal projected = openNotional.add(event.quantity().multiply(event.price()));
        if (projected.compareTo(riskProperties.maxNotionalPerClient()) > 0) {
            return Optional.of("Open notional %s would exceed client limit %s"
                    .formatted(projected, riskProperties.maxNotionalPerClient()));
        }
        return Optional.empty();
    }

    private Optional<String> checkVelocityLimit(OrderEvent event) {
        int count = velocityTracker.recordAndCount(
                event.clientId(), Instant.now(), Duration.ofSeconds(riskProperties.windowSeconds()));
        if (count > riskProperties.maxOrdersPerWindow()) {
            return Optional.of("%d orders from client in the last %ds exceeds the limit of %d"
                    .formatted(count, riskProperties.windowSeconds(), riskProperties.maxOrdersPerWindow()));
        }
        return Optional.empty();
    }

    private void reject(OrderEvent event, String reason) {
        AnomalyResult enriched = anomalyDetector.explain(new AnomalyContext("order:" + event.orderId(), reason));

        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.setStatus(OrderStatus.REJECTED);
            orderRepository.save(order);
        });

        riskAlertRepository.save(RiskAlert.builder()
                .logId(UUID.randomUUID())
                .orderId(event.orderId())
                .clientId(event.clientId())
                .riskLevel(RiskLevel.HIGH)
                .reason(enriched.explanation())
                .createdAt(Instant.now())
                .build());

        kafkaEventPublisher.publish(
                topics.riskAlerts(),
                event.clientId(),
                new RiskAlertEvent(event.orderId(), event.clientId(), RiskLevel.HIGH, enriched.explanation(), Instant.now()));

        log.warn("Order rejected by risk service: orderId={}, reason={}", event.orderId(), enriched.explanation());
    }

    private void validate(OrderEvent event) {
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.setStatus(OrderStatus.VALIDATED);
            orderRepository.save(order);
        });

        kafkaEventPublisher.publish(
                topics.ordersValidated(),
                event.symbol(),
                new OrderValidatedEvent(
                        event.orderId(),
                        event.clientId(),
                        event.symbol(),
                        event.side(),
                        event.quantity(),
                        event.price(),
                        event.createdAt()));
    }
}
