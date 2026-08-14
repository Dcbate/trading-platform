package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.chronicle.TradeJournalWriter;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.domain.Trade;
import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import com.dcbate.tradingplatform.trading.repository.TradeRepository;
import com.dcbate.tradingplatform.trading.websocket.OrderStreamHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** @see ExecutionService */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionServiceImpl implements ExecutionService {

    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final TradeJournalWriter tradeJournalWriter;
    private final OrderStreamHandler orderStreamHandler;

    @Override
    @Transactional
    public void recordTrade(TradeEvent event) {
        tradeRepository.save(Trade.builder()
                .tradeId(event.tradeId())
                .buyOrderId(event.buyOrderId())
                .sellOrderId(event.sellOrderId())
                .symbol(event.symbol())
                .quantity(event.quantity())
                .price(event.price())
                .createdAt(event.createdAt())
                .build());

        updateOrderStatus(event.buyOrderId(), event.buyOrderStatus());
        updateOrderStatus(event.sellOrderId(), event.sellOrderStatus());

        tradeJournalWriter.append(event);

        log.info(
                "Trade executed: tradeId={}, symbol={}, quantity={}, price={}",
                event.tradeId(), event.symbol(), event.quantity(), event.price());
    }

    private void updateOrderStatus(UUID orderId, OrderStatus status) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(status);
            if (status == OrderStatus.FILLED) {
                order.setFilledAt(Instant.now());
            }
            Order saved = orderRepository.save(order);
            orderStreamHandler.publish(OrderResponse.from(saved));
        });
    }
}
