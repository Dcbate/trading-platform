package com.dcbate.tradingplatform.trading.service.matching;

import com.dcbate.tradingplatform.domain.OrderSide;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Single-currencyPair price/time-priority order book. Buys are keyed highest-price-first, sells
 * lowest-price-first; FIFO within a price level via insertion-ordered deques. Matching for one
 * currencyPair is synchronized on this instance — different currencyPairs never contend with each other,
 * which is what actually matters for throughput (a truly wait-free multi-currencyPair book is out of
 * scope for Phase 1; see ARCHITECTURE.md).
 */
public class OrderBook {

    private final NavigableMap<BigDecimal, Deque<OrderBookEntry>> buys = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<BigDecimal, Deque<OrderBookEntry>> sells = new TreeMap<>();

    public synchronized List<Match> match(OrderBookEntry incoming) {
        NavigableMap<BigDecimal, Deque<OrderBookEntry>> opposite = oppositeSide(incoming.getSide());
        List<Match> matches = new ArrayList<>();

        while (!incoming.isFullyFilled()) {
            Map.Entry<BigDecimal, Deque<OrderBookEntry>> bestLevel = opposite.firstEntry();
            if (bestLevel == null || !crosses(incoming, bestLevel.getKey())) {
                break;
            }

            Deque<OrderBookEntry> level = bestLevel.getValue();
            OrderBookEntry resting = level.peekFirst();
            if (resting == null) {
                opposite.remove(bestLevel.getKey());
                continue;
            }

            BigDecimal fillQty = incoming.getRemainingQuantity().compareTo(resting.getRemainingQuantity()) <= 0
                    ? incoming.getRemainingQuantity()
                    : resting.getRemainingQuantity();

            incoming.setRemainingQuantity(incoming.getRemainingQuantity().subtract(fillQty));
            resting.setRemainingQuantity(resting.getRemainingQuantity().subtract(fillQty));
            matches.add(new Match(incoming, resting, fillQty, bestLevel.getKey()));

            if (resting.isFullyFilled()) {
                level.pollFirst();
                if (level.isEmpty()) {
                    opposite.remove(bestLevel.getKey());
                }
            }
        }

        if (!incoming.isFullyFilled()) {
            ownSide(incoming.getSide()).computeIfAbsent(incoming.getPrice(), p -> new ArrayDeque<>()).addLast(incoming);
        }

        return matches;
    }

    public synchronized int restingOrderCount() {
        return buys.values().stream().mapToInt(Deque::size).sum()
                + sells.values().stream().mapToInt(Deque::size).sum();
    }

    private boolean crosses(OrderBookEntry incoming, BigDecimal bestOppositePrice) {
        return incoming.getSide() == OrderSide.BUY
                ? incoming.getPrice().compareTo(bestOppositePrice) >= 0
                : incoming.getPrice().compareTo(bestOppositePrice) <= 0;
    }

    private NavigableMap<BigDecimal, Deque<OrderBookEntry>> oppositeSide(OrderSide side) {
        return side == OrderSide.BUY ? sells : buys;
    }

    private NavigableMap<BigDecimal, Deque<OrderBookEntry>> ownSide(OrderSide side) {
        return side == OrderSide.BUY ? buys : sells;
    }
}
