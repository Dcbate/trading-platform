package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.OrderValidatedEvent;
import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import com.dcbate.tradingplatform.trading.repository.TradeRepository;
import com.dcbate.tradingplatform.trading.service.matching.Match;
import com.dcbate.tradingplatform.trading.service.matching.OrderBook;
import com.dcbate.tradingplatform.trading.service.matching.OrderBookEntry;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
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
 * The matching core (Low-Latency Pattern 2). One {@link OrderBook} per currencyPair, looked up through
 * a {@link ConcurrentHashMap} so unrelated currencyPairs never block each other; matching itself is
 * synchronized per-book (see {@link OrderBook}) to keep price/time priority correct.
 *
 * <p>The book is in-memory only and empty on every restart — {@link #recoverRestingOrders()} rebuilds
 * it from Postgres before anything else can touch it, so a resting order survives an app restart
 * instead of becoming permanently unmatchable. See {@code docs/HOW_A_TRADE_FILLS.md} for the bug
 * this closes, found live rather than by code review.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingEngineServiceImpl implements MatchingEngineService {

    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    private final AtomicLong sequenceGenerator = new AtomicLong();

    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;

    /**
     * Runs during this bean's own construction, before any dependent bean (notably
     * {@code MatchingEngineConsumerRunner}, which takes this service as a constructor argument) can
     * be constructed itself — Spring's constructor-injection ordering already guarantees this
     * completes before a single new order can reach the book, with no extra {@code @DependsOn}
     * needed. Orders still resting or partially filled at shutdown can never have crossed each
     * other (if they had, the matching engine would have already resolved that and neither would
     * still be resting) — replaying them through the ordinary {@link #match} path in original
     * arrival order is therefore safe and reuses the exact same logic live traffic uses, rather than
     * a separate insert-without-matching code path that could drift out of sync with it.
     */
    @PostConstruct
    void recoverRestingOrders() {
        List<Order> resting = orderRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(OrderStatus.VALIDATED, OrderStatus.PARTIALLY_FILLED));
        for (Order order : resting) {
            BigDecimal remaining = remainingQuantity(order);
            if (remaining.signum() <= 0) {
                continue;
            }
            match(new OrderValidatedEvent(
                    order.getOrderId(), order.getClientId(), order.getCurrencyPair(),
                    order.getSide(), remaining, order.getPrice(), order.getCreatedAt()));
        }
        if (!resting.isEmpty()) {
            log.info("Recovered {} resting order(s) into the matching engine's order book after startup", resting.size());
        }
    }

    private BigDecimal remainingQuantity(Order order) {
        if (order.getStatus() == OrderStatus.VALIDATED) {
            return order.getQuantity();
        }
        return order.getQuantity().subtract(tradeRepository.sumQuantityByOrderId(order.getOrderId()));
    }

    @Override
    public List<TradeEvent> match(OrderValidatedEvent event) {
        OrderBook book = books.computeIfAbsent(event.currencyPair(), currencyPair -> new OrderBook());
        OrderBookEntry incoming = new OrderBookEntry(
                event.orderId(),
                event.clientId(),
                event.side(),
                event.price(),
                event.quantity(),
                sequenceGenerator.incrementAndGet());

        List<Match> matches = book.match(incoming);
        List<TradeEvent> tradeEvents = matches.stream().map(m -> toTradeEvent(m, event.currencyPair())).toList();
        tradeEvents.forEach(trade -> kafkaEventPublisher.publish(topics.trades(), trade.currencyPair(), trade));

        log.info(
                "Matched orderId={}, currencyPair={}, fills={}, remainingQty={}",
                event.orderId(), event.currencyPair(), matches.size(), incoming.getRemainingQuantity());

        return tradeEvents;
    }

    @Override
    public int orderBookDepth() {
        return books.values().stream().mapToInt(OrderBook::restingOrderCount).sum();
    }

    private TradeEvent toTradeEvent(Match match, String currencyPair) {
        OrderBookEntry buyEntry = match.incoming().getSide() == OrderSide.BUY ? match.incoming() : match.resting();
        OrderBookEntry sellEntry = match.incoming().getSide() == OrderSide.SELL ? match.incoming() : match.resting();

        return new TradeEvent(
                UUID.randomUUID(),
                buyEntry.getOrderId(),
                sellEntry.getOrderId(),
                currencyPair,
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
