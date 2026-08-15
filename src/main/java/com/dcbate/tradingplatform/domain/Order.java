package com.dcbate.tradingplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A buy or sell order. {@code OrderService} is the only writer of the initial {@code PENDING}
 * row; {@code RiskService} writes {@code VALIDATED}/{@code REJECTED}; {@code ExecutionService}
 * writes {@code PARTIALLY_FILLED}/{@code FILLED} as fills come in from the Matching Engine.
 *
 * <p>{@code accountId} is optional — null for the FX desk's dealer-submitted orders (no
 * settlement), set for client-submitted stock orders, which {@code ExecutionServiceImpl} settles
 * against that account's cash balance and a {@code Position} row on every fill.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    private UUID orderId;

    @Column(nullable = false)
    private String clientId;

    private UUID accountId;

    @Column(nullable = false)
    private String currencyPair;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSide side;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(nullable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant filledAt;
}
