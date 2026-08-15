package com.dcbate.tradingplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * How many shares of a symbol an account holds, and what it paid for them on average.
 * {@code avgCost} is the standard weighted-average cost basis: a buy fill folds its price into
 * the average, a sell fill reduces {@code quantity} but never changes {@code avgCost} — see
 * {@code ExecutionServiceImpl}.
 */
@Entity
@Table(name = "positions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Position {

    @Id
    private UUID positionId;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private String clientId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(nullable = false)
    private BigDecimal avgCost;

    @Column(nullable = false)
    private Instant updatedAt;
}
