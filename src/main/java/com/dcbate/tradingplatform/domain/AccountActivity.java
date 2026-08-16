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
 * The persisted twin of {@code AccountActivityEvent} — {@code AccountServiceImpl} writes one of
 * these alongside every Kafka publish, so a deposit/withdrawal/conversion/closure is queryable
 * (backs the bank statement) instead of only existing as a fire-and-forget audit event.
 */
@Entity
@Table(name = "account_activity")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountActivity {

    @Id
    private UUID activityId;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountActivityType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private BigDecimal balanceAfter;

    private UUID relatedAccountId;

    private BigDecimal rate;

    @Column(nullable = false)
    private Instant occurredAt;
}
