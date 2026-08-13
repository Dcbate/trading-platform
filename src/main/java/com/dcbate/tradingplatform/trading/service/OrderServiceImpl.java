package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.exception.OrderNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.OrderEvent;
import com.dcbate.tradingplatform.trading.api.dto.OrderRequest;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;

    @Override
    @Transactional
    public OrderResponse submitOrder(OrderRequest request) {
        Order order = Order.builder()
                .orderId(UUID.randomUUID())
                .clientId(request.clientId())
                .symbol(request.symbol())
                .side(request.side())
                .quantity(request.quantity())
                .price(request.price())
                .status(OrderStatus.PENDING)
                .createdAt(Instant.now())
                .build();

        Order saved = orderRepository.save(order);

        kafkaEventPublisher.publish(
                topics.orders(),
                saved.getSymbol(),
                new OrderEvent(
                        saved.getOrderId(),
                        saved.getClientId(),
                        saved.getSymbol(),
                        saved.getSide(),
                        saved.getQuantity(),
                        saved.getPrice(),
                        saved.getCreatedAt()));

        log.info("Order accepted: orderId={}, clientId={}, symbol={}, side={}",
                saved.getOrderId(), saved.getClientId(), saved.getSymbol(), saved.getSide());

        return OrderResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
