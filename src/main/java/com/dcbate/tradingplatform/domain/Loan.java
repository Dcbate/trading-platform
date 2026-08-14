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
 * A client's loan against one of their own accounts. {@code outstandingPrincipal} and
 * {@code accruedInterest} are tracked separately because a repayment pays down accrued interest
 * first, then principal (see {@code LoanServiceImpl.repay}) — the real amortization order.
 * {@code lastAccrualAt} is what {@code LoanServiceImpl.accrueInterest()}'s scheduled job advances
 * each run, so interest is never double-counted across runs.
 */
@Entity
@Table(name = "loans")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Loan {

    @Id
    private UUID loanId;

    @Column(nullable = false)
    private String clientId;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private BigDecimal principal;

    @Column(nullable = false)
    private BigDecimal outstandingPrincipal;

    @Column(nullable = false)
    private BigDecimal interestRateAnnualPercent;

    @Column(nullable = false)
    private BigDecimal accruedInterest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastAccrualAt;
}
