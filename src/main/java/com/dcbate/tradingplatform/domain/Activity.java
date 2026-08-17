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
 * One immutable row in a client's activity/audit trail — a deposit, withdrawal, conversion,
 * account closure, loan origination, repayment, or one leg of a transfer. Written once, by the
 * service that caused it, with the human-readable {@code description} and signed {@code amount}
 * already computed at write time (the service already has every object it needs in hand — the
 * account, the counterparty, the rate — so there's nothing left to re-derive later). This is what
 * lets {@code BankStatementServiceImpl} read this table directly instead of reconstructing the
 * same information from five different repositories at request time.
 */
@Entity
@Table(name = "activity")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Activity {

    @Id
    private UUID activityId;

    @Column(nullable = false)
    private String clientId;

    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivityType type;

    @Column(nullable = false)
    private BigDecimal amount;

    private String currency;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private Instant occurredAt;
}
