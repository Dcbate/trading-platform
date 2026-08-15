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
 * A loan taken during a Game Mode session — unlike a real {@code Loan}, there's no repayment
 * flow: a session only lasts 15-30 minutes, so "pay it down before the timer runs out" isn't a
 * meaningful mechanic. Interest owed is computed on the fly from {@code originatedAt} at
 * valuation time ({@code GameServiceImpl.interestOwed}) rather than accrued and persisted
 * incrementally, since nothing needs it to survive between reads.
 */
@Entity
@Table(name = "game_loans")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameLoan {

    @Id
    private UUID gameLoanId;

    @Column(nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private BigDecimal principal;

    @Column(nullable = false)
    private BigDecimal rateAnnualPercent;

    @Column(nullable = false)
    private Instant originatedAt;
}
