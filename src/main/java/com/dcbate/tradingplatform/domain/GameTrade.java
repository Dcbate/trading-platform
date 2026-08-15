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
 * One fill in a Game Mode session — instant, at the current simulated market price, not matched
 * through an order book (there's no counterparty to match against in a single-player game).
 * {@code realizedPnl} is only set on a SELL: {@code (fillPrice - avgCostAtSaleTime) * quantity -
 * fee}, the number the win/lose screen's "best trade" stat reads from.
 */
@Entity
@Table(name = "game_trades")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameTrade {

    @Id
    private UUID tradeId;

    @Column(nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSide side;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private BigDecimal fee;

    private BigDecimal realizedPnl;

    @Column(nullable = false)
    private Instant createdAt;
}
