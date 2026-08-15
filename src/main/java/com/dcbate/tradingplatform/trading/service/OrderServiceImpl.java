package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.domain.Position;
import com.dcbate.tradingplatform.exception.AccountNotActiveException;
import com.dcbate.tradingplatform.exception.AccountNotFoundException;
import com.dcbate.tradingplatform.exception.InsufficientFundsException;
import com.dcbate.tradingplatform.exception.InsufficientPositionException;
import com.dcbate.tradingplatform.exception.OrderNotFoundException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.OrderEvent;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.trading.api.dto.OrderRequest;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import com.dcbate.tradingplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** @see OrderService */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;

    @Override
    @Transactional
    public OrderResponse submitOrder(OrderRequest request, CallerPrincipal caller) {
        caller.requireOwner(request.clientId());
        if (request.accountId() != null) {
            checkFundingAccount(request, caller);
        }

        Order order = Order.builder()
                .orderId(UUID.randomUUID())
                .clientId(request.clientId())
                .accountId(request.accountId())
                .currencyPair(request.currencyPair())
                .side(request.side())
                .quantity(request.quantity())
                .price(request.price())
                .status(OrderStatus.PENDING)
                .createdAt(Instant.now())
                .build();

        Order saved = orderRepository.save(order);

        kafkaEventPublisher.publish(
                topics.orders(),
                saved.getCurrencyPair(),
                new OrderEvent(
                        saved.getOrderId(),
                        saved.getClientId(),
                        saved.getCurrencyPair(),
                        saved.getSide(),
                        saved.getQuantity(),
                        saved.getPrice(),
                        saved.getCreatedAt()));

        log.info("Order accepted: orderId={}, clientId={}, currencyPair={}, side={}",
                saved.getOrderId(), saved.getClientId(), saved.getCurrencyPair(), saved.getSide());

        return OrderResponse.from(saved);
    }

    /**
     * Point-in-time check only (same honest caveat as {@code PaymentServiceImpl}'s balance
     * check) — a real hold on cash/shares would need to happen here to be race-proof against two
     * concurrent orders; the actual debit/credit still happens for real at fill time in
     * {@code ExecutionServiceImpl}.
     */
    private void checkFundingAccount(OrderRequest request, CallerPrincipal caller) {
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new AccountNotFoundException(request.accountId()));
        caller.requireOwner(account.getClientId());
        if (!account.getClientId().equals(request.clientId())) {
            throw new AccountNotFoundException(request.accountId());
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(request.accountId());
        }

        if (request.side() == OrderSide.BUY) {
            BigDecimal notional = request.quantity().multiply(request.price());
            if (account.getBalance().compareTo(notional) < 0) {
                throw new InsufficientFundsException(request.accountId(), notional, account.getBalance());
            }
        } else {
            BigDecimal owned = positionRepository.findByAccountIdAndSymbol(request.accountId(), request.currencyPair())
                    .map(Position::getQuantity)
                    .orElse(BigDecimal.ZERO);
            if (owned.compareTo(request.quantity()) < 0) {
                throw new InsufficientPositionException(request.accountId(), request.currencyPair(), request.quantity(), owned);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId, CallerPrincipal caller) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        caller.requireOwner(order.getClientId());
        return OrderResponse.from(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> listOrdersForClient(String clientId, CallerPrincipal caller) {
        caller.requireOwner(clientId);
        return orderRepository.findByClientIdOrderByCreatedAtDesc(clientId).stream().map(OrderResponse::from).toList();
    }
}
