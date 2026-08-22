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
 * One Game Mode run: a client picks a {@link GameDifficulty}, starts with its seed cash, and has
 * until {@code endsAt} to grow net worth (cash + positions + savings - loans owed) to the
 * difficulty's goal. {@code cash} and {@code savingsBalance} are the only balances mutated
 * directly by trades/loans/savings moves; positions and loans live in their own tables and are
 * combined with the live simulated market price at valuation time.
 *
 * <p>{@code savingsBalance}/{@code savingsLastAccrualAt} follow the same "settle on read, don't
 * write on every poll" pattern as {@code GameLoan.accruedInterest}/{@code lastAccrualAt} — see
 * {@code GameServiceImpl.pendingSavingsInterest} — except savings never needs a separate
 * "already-settled" bucket the way a loan does, since a deposit/withdrawal has no interest-first
 * repayment ordering to preserve; pending interest is simply folded straight into the balance
 * whenever it's settled.
 */
@Entity
@Table(name = "game_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameSession {

    @Id
    private UUID sessionId;

    @Column(nullable = false)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GameDifficulty difficulty;

    @Column(nullable = false)
    private BigDecimal cash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GameStatus status;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant endsAt;

    private Instant finishedAt;

    private BigDecimal finalNetWorth;

    private BigDecimal savingsBalance;

    private Instant savingsLastAccrualAt;

    /** When this session may next activate a speed boost — see {@code GameServiceImpl.activateSpeedBoost}. */
    private Instant speedBoostAvailableAt;

    private boolean advisorHired;

    private Instant advisorHiredAt;

    private Instant advisorLastTipAt;

    private String advisorTipSymbol;

    @Enumerated(EnumType.STRING)
    private OrderSide advisorTipSide;

    /** Running total of dividends swept into cash so far this session — see {@code GameServiceImpl.settleDividends}. */
    private BigDecimal totalDividendsPaid;

    /** Internal high-water mark of realized P&L already taxed — not exposed on the DTO, see {@code GameServiceImpl.settleTax}. */
    private BigDecimal totalRealizedPnlTaxed;

    /** Running total of tax actually deducted from cash so far this session. */
    private BigDecimal totalTaxPaid;

    private Instant taxLastSettledAt;
}
