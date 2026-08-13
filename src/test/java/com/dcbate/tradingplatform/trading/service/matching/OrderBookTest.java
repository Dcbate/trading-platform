package com.dcbate.tradingplatform.trading.service.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcbate.tradingplatform.domain.OrderSide;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private final OrderBook book = new OrderBook();

    private OrderBookEntry entry(OrderSide side, String price, String quantity, long sequence) {
        return new OrderBookEntry(UUID.randomUUID(), "client-" + sequence, side, new BigDecimal(price), new BigDecimal(quantity), sequence);
    }

    @Test
    void restsOrderWhenNoOppositeSideExists() {
        List<Match> matches = book.match(entry(OrderSide.BUY, "100.00", "10", 1));

        assertThat(matches).isEmpty();
        assertThat(book.restingOrderCount()).isEqualTo(1);
    }

    @Test
    void fullyMatchesEqualQuantitiesAtCrossingPrice() {
        book.match(entry(OrderSide.SELL, "100.00", "10", 1));

        List<Match> matches = book.match(entry(OrderSide.BUY, "100.00", "10", 2));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).quantity()).isEqualByComparingTo("10");
        assertThat(matches.get(0).price()).isEqualByComparingTo("100.00");
        assertThat(book.restingOrderCount()).isZero();
    }

    @Test
    void executesAtTheRestingOrdersPrice() {
        book.match(entry(OrderSide.SELL, "99.50", "10", 1));

        List<Match> matches = book.match(entry(OrderSide.BUY, "101.00", "10", 2));

        assertThat(matches.get(0).price()).isEqualByComparingTo("99.50");
    }

    @Test
    void partiallyFillsAndRestsTheRemainder() {
        book.match(entry(OrderSide.SELL, "100.00", "4", 1));

        List<Match> matches = book.match(entry(OrderSide.BUY, "100.00", "10", 2));

        assertThat(matches.get(0).quantity()).isEqualByComparingTo("4");
        assertThat(book.restingOrderCount()).isEqualTo(1);
    }

    @Test
    void matchesFifoWithinSamePriceLevel() {
        OrderBookEntry firstSeller = entry(OrderSide.SELL, "100.00", "5", 1);
        OrderBookEntry secondSeller = entry(OrderSide.SELL, "100.00", "5", 2);
        book.match(firstSeller);
        book.match(secondSeller);

        List<Match> matches = book.match(entry(OrderSide.BUY, "100.00", "5", 3));

        assertThat(matches.get(0).resting().getOrderId()).isEqualTo(firstSeller.getOrderId());
    }

    @Test
    void prefersBestPriceOverArrivalOrder() {
        book.match(entry(OrderSide.SELL, "101.00", "5", 1));
        OrderBookEntry cheaperSeller = entry(OrderSide.SELL, "99.00", "5", 2);
        book.match(cheaperSeller);

        List<Match> matches = book.match(entry(OrderSide.BUY, "101.00", "5", 3));

        assertThat(matches.get(0).resting().getOrderId()).isEqualTo(cheaperSeller.getOrderId());
    }

    @Test
    void doesNotCrossWhenBuyPriceBelowBestAsk() {
        book.match(entry(OrderSide.SELL, "100.00", "5", 1));

        List<Match> matches = book.match(entry(OrderSide.BUY, "99.00", "5", 2));

        assertThat(matches).isEmpty();
        assertThat(book.restingOrderCount()).isEqualTo(2);
    }
}
