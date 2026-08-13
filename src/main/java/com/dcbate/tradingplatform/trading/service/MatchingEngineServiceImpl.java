package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.OrderValidatedEvent;
import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import com.dcbate.tradingplatform.trading.service.matching.Match;
import com.dcbate.tradingplatform.trading.service.matching.OrderBook;
import com.dcbate.tradingplatform.trading.service.matching.OrderBookEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The matching core (Low-Latency Pattern 2). One {@link OrderBook} per symbol, looked up through
 * a {@link ConcurrentHashMap} so unrelated symbols never block each other; matching itself is
 * synchronized per-book (see {@link OrderBook}) to keep price/time priority correct.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingEngineServiceImpl implements MatchingEngineService {

    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    private final AtomicLong sequenceGenerator = new AtomicLong();

    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;

    @Override
    public List<TradeEvent> match(OrderValidatedEvent event) {
        OrderBook book = books.computeIfAbsent(event.symbol(), symbol -> new OrderBook());
        OrderBookEntry incoming = new OrderBookEntry(
                event.orderId(),
                event.clientId(),
                event.side(),
                event.price(),
                event.quantity(),
                sequenceGenerator.incrementAndGet());

        List<Match> matches = book.match(incoming);
        List<TradeEvent> tradeEvents = matches.stream().map(m -> toTradeEvent(m, event.symbol())).toList();
        tradeEvents.forEach(trade -> kafkaEventPublisher.publish(topics.trades(), trade.symbol(), trade));

        log.info(
                "Matched orderId={}, symbol={}, fills={}, remainingQty={}",
                event.orderId(), event.symbol(), matches.size(), incoming.getRemainingQuantity());

        return tradeEvents;
    }

    @Override
    public int orderBookDepth() {
        return books.values().stream().mapToInt(OrderBook::restingOrderCount).sum();
    }

    private TradeEvent toTradeEvent(Match match, String symbol) {
        OrderBookEntry buyEntry = match.incoming().getSide() == OrderSide.BUY ? match.incoming() : match.resting();
        OrderBookEntry sellEntry = match.incoming().getSide() == OrderSide.SELL ? match.incoming() : match.resting();

        return new TradeEvent(
                UUID.randomUUID(),
                buyEntry.getOrderId(),
                sellEntry.getOrderId(),
                symbol,
                match.quantity(),
                match.price(),
                statusFor(buyEntry),
                statusFor(sellEntry),
                Instant.now());
    }

    private OrderStatus statusFor(OrderBookEntry entry) {
        return entry.isFullyFilled() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }
}
