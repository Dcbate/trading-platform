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
 * A loan taken during a Game Mode session. {@code principal} is the original amount borrowed —
 * fixed, for display/stats. {@code outstandingPrincipal} and {@code accruedInterest} are what's
 * actually still owed and move as the loan is repaid ({@code GameServiceImpl.repayLoan}), the
 * same three-way split the real {@code Loan} entity uses. Unlike the real loan, there's no
 * scheduled accrual job — {@code accruedInterest} only gets settled (the pending amount since
 * {@code lastAccrualAt} folded in) at the moments that actually need an exact number: a
 * repayment, or the session ending. A live read in between (a session poll) computes the pending
 * amount on the fly without writing it, the same non-mutating style the original interest-only
 * version of this class used — a session never outlives 30 minutes, so there's nothing here that
 * needs to survive a restart or be visible outside this one read.
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
    private BigDecimal outstandingPrincipal;

    @Column(nullable = false)
    private BigDecimal accruedInterest;

    @Column(nullable = false)
    private BigDecimal rateAnnualPercent;

    @Column(nullable = false)
    private Instant originatedAt;

    @Column(nullable = false)
    private Instant lastAccrualAt;
}
