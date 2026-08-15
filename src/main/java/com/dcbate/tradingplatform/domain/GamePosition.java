package com.dcbate.tradingplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A Game Mode session's holding in one symbol — same weighted-average-cost shape as the real {@link Position}, scoped to a session instead of an account. */
@Entity
@Table(name = "game_positions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GamePosition {

    @Id
    private UUID positionId;

    @Column(nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(nullable = false)
    private BigDecimal avgCost;
}
