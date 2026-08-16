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
 * The persisted twin of the {@code LoanEvent} Kafka record — {@code LoanServiceImpl} writes one
 * of these alongside every publish, on origination and on each repayment. {@code Loan} itself
 * only tracks current state ({@code outstandingPrincipal}, {@code accruedInterest}); this table
 * is what makes individual repayments queryable (backs the bank statement), not just the latest
 * balance.
 */
@Entity
@Table(name = "loan_activity")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanActivity {

    @Id
    private UUID activityId;

    @Column(nullable = false)
    private UUID loanId;

    @Column(nullable = false)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanEventType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private BigDecimal outstandingPrincipal;

    @Column(nullable = false)
    private BigDecimal accruedInterest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanStatus status;

    @Column(nullable = false)
    private Instant occurredAt;
}
