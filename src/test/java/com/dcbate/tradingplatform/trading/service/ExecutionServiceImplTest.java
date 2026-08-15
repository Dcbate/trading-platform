package com.dcbate.tradingplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.chronicle.TradeJournalWriter;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.AccountType;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionServiceImplTest {

    @Mock
    private TradeRepository tradeRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private TradeJournalWriter tradeJournalWriter;

    @Mock
    private OrderStreamHandler orderStreamHandler;

    private ExecutionServiceImpl executionService;

    @BeforeEach
    void setUp() {
        executionService = new ExecutionServiceImpl(
                tradeRepository, orderRepository, accountRepository, positionRepository, tradeJournalWriter, orderStreamHandler,
                new SimpleMeterRegistry());
    }

    @Test
    void recordTradePersistsTradeAndJournalsIt() {
        TradeEvent event = tradeEvent(OrderStatus.FILLED, OrderStatus.FILLED);
        when(orderRepository.findById(any())).thenReturn(Optional.empty());

        executionService.recordTrade(event);

        verify(tradeRepository).save(any(Trade.class));
        verify(tradeJournalWriter).append(event);
    }

    @Test
    void fullyFilledOrderGetsFilledAtTimestamp() {
        Order buyOrder = Order.builder().orderId(UUID.randomUUID()).status(OrderStatus.VALIDATED).build();
        TradeEvent event = tradeEvent(OrderStatus.FILLED, OrderStatus.PARTIALLY_FILLED);
        when(orderRepository.findById(event.buyOrderId())).thenReturn(Optional.of(buyOrder));
        when(orderRepository.findById(event.sellOrderId())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        executionService.recordTrade(event);

        assertThat(buyOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(buyOrder.getFilledAt()).isNotNull();
        verify(orderStreamHandler, times(1)).publish(any(OrderResponse.class));
    }

    @Test
    void buyFillWithFundingAccountDebitsCashAndOpensAPosition() {
        UUID accountId = UUID.randomUUID();
        Order buyOrder = Order.builder().orderId(UUID.randomUUID()).accountId(accountId).side(OrderSide.BUY)
                .currencyPair("AAPL").status(OrderStatus.VALIDATED).build();
        Account account = Account.builder().accountId(accountId).clientId("client-1").accountType(AccountType.BROKERAGE)
                .currency("USD").balance(new BigDecimal("5000.00")).status(AccountStatus.ACTIVE).createdAt(Instant.now()).build();
        TradeEvent event = new TradeEvent(UUID.randomUUID(), buyOrder.getOrderId(), UUID.randomUUID(), "AAPL",
                new BigDecimal("10"), new BigDecimal("190.00"), OrderStatus.FILLED, OrderStatus.FILLED, Instant.now());

        when(orderRepository.findById(buyOrder.getOrderId())).thenReturn(Optional.of(buyOrder));
        when(orderRepository.findById(event.sellOrderId())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(positionRepository.findByAccountIdAndSymbol(accountId, "AAPL")).thenReturn(Optional.empty());

        executionService.recordTrade(event);

        assertThat(account.getBalance()).isEqualByComparingTo("3100.00");
        var positionCaptor = org.mockito.ArgumentCaptor.forClass(Position.class);
        verify(positionRepository).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().getQuantity()).isEqualByComparingTo("10");
        assertThat(positionCaptor.getValue().getAvgCost()).isEqualByComparingTo("190.00");
    }

    @Test
    void sellFillWithFundingAccountCreditsCashAndReducesPosition() {
        UUID accountId = UUID.randomUUID();
        Order sellOrder = Order.builder().orderId(UUID.randomUUID()).accountId(accountId).side(OrderSide.SELL)
                .currencyPair("AAPL").status(OrderStatus.VALIDATED).build();
        Account account = Account.builder().accountId(accountId).clientId("client-1").accountType(AccountType.BROKERAGE)
                .currency("USD").balance(new BigDecimal("100.00")).status(AccountStatus.ACTIVE).createdAt(Instant.now()).build();
        Position existing = Position.builder().positionId(UUID.randomUUID()).accountId(accountId).clientId("client-1")
                .symbol("AAPL").quantity(new BigDecimal("10")).avgCost(new BigDecimal("190.00")).build();
        TradeEvent event = new TradeEvent(UUID.randomUUID(), UUID.randomUUID(), sellOrder.getOrderId(), "AAPL",
                new BigDecimal("4"), new BigDecimal("200.00"), OrderStatus.FILLED, OrderStatus.FILLED, Instant.now());

        when(orderRepository.findById(event.buyOrderId())).thenReturn(Optional.empty());
        when(orderRepository.findById(sellOrder.getOrderId())).thenReturn(Optional.of(sellOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(positionRepository.findByAccountIdAndSymbol(accountId, "AAPL")).thenReturn(Optional.of(existing));

        executionService.recordTrade(event);

        assertThat(account.getBalance()).isEqualByComparingTo("900.00");
        assertThat(existing.getQuantity()).isEqualByComparingTo("6");
        assertThat(existing.getAvgCost()).isEqualByComparingTo("190.00");
    }

    private TradeEvent tradeEvent(OrderStatus buyStatus, OrderStatus sellStatus) {
        return new TradeEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "EUR/USD",
                new BigDecimal("10"), new BigDecimal("150.00"), buyStatus, sellStatus, Instant.now());
    }
}
