package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.chronicle.TradeJournalWriter;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.domain.Position;
import com.dcbate.tradingplatform.domain.Trade;
import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import com.dcbate.tradingplatform.trading.repository.PositionRepository;
import com.dcbate.tradingplatform.trading.repository.TradeRepository;
import com.dcbate.tradingplatform.trading.websocket.OrderStreamHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
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
    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final TradeJournalWriter tradeJournalWriter;
    private final OrderStreamHandler orderStreamHandler;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public void recordTrade(TradeEvent event) {
        meterRegistry.counter("fx.trades.total", "currencyPair", event.currencyPair()).increment();
        meterRegistry.summary("fx.trade.volume", "currencyPair", event.currencyPair()).record(event.quantity().doubleValue());

        tradeRepository.save(Trade.builder()
                .tradeId(event.tradeId())
                .buyOrderId(event.buyOrderId())
                .sellOrderId(event.sellOrderId())
                .currencyPair(event.currencyPair())
                .quantity(event.quantity())
                .price(event.price())
                .createdAt(event.createdAt())
                .build());

        updateOrderStatus(event.buyOrderId(), event.buyOrderStatus(), event.quantity(), event.price());
        updateOrderStatus(event.sellOrderId(), event.sellOrderStatus(), event.quantity(), event.price());

        tradeJournalWriter.append(event);

        log.info(
                "Trade executed: tradeId={}, currencyPair={}, quantity={}, price={}",
                event.tradeId(), event.currencyPair(), event.quantity(), event.price());
    }

    private void updateOrderStatus(UUID orderId, OrderStatus status, BigDecimal fillQuantity, BigDecimal fillPrice) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(status);
            if (status == OrderStatus.FILLED) {
                order.setFilledAt(Instant.now());
            }
            Order saved = orderRepository.save(order);

            if (saved.getAccountId() != null) {
                settleFill(saved, fillQuantity, fillPrice);
            }

            orderStreamHandler.publish(OrderResponse.from(saved));
        });
    }

    /**
     * Real cash/position settlement for an order that carries a funding account (stock orders —
     * see {@code Order.accountId}'s javadoc). Deliberately not applied to FX pair orders: a
     * currency pair involves two currencies, not a single cash debit/credit against one account,
     * and that's a genuinely separate problem this doesn't attempt to solve.
     */
    private void settleFill(Order order, BigDecimal fillQuantity, BigDecimal fillPrice) {
        Account account = accountRepository.findById(order.getAccountId()).orElse(null);
        if (account == null) {
            log.error("Order {} references missing account {}, skipping settlement", order.getOrderId(), order.getAccountId());
            return;
        }

        BigDecimal notional = fillQuantity.multiply(fillPrice);
        Position position = positionRepository.findByAccountIdAndSymbol(account.getAccountId(), order.getCurrencyPair())
                .orElseGet(() -> Position.builder()
                        .positionId(UUID.randomUUID())
                        .accountId(account.getAccountId())
                        .clientId(account.getClientId())
                        .symbol(order.getCurrencyPair())
                        .quantity(BigDecimal.ZERO)
                        .avgCost(BigDecimal.ZERO)
                        .build());

        if (order.getSide() == OrderSide.BUY) {
            account.setBalance(account.getBalance().subtract(notional));
            BigDecimal newQuantity = position.getQuantity().add(fillQuantity);
            BigDecimal newCostBasis = position.getQuantity().multiply(position.getAvgCost()).add(notional);
            position.setAvgCost(newQuantity.signum() == 0 ? BigDecimal.ZERO : newCostBasis.divide(newQuantity, 8, java.math.RoundingMode.HALF_UP));
            position.setQuantity(newQuantity);
        } else {
            account.setBalance(account.getBalance().add(notional));
            position.setQuantity(position.getQuantity().subtract(fillQuantity).max(BigDecimal.ZERO));
            // avgCost is deliberately untouched on a sell — average-cost accounting only ever
            // recomputes the basis on a buy.
        }

        position.setUpdatedAt(Instant.now());
        accountRepository.save(account);
        positionRepository.save(position);

        log.info("Settled fill: orderId={}, accountId={}, side={}, symbol={}, fillQuantity={}, fillPrice={}, balanceAfter={}",
                order.getOrderId(), account.getAccountId(), order.getSide(), order.getCurrencyPair(), fillQuantity, fillPrice, account.getBalance());
    }
}
