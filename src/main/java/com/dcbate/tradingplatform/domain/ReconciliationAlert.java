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

/** A ledger discrepancy above the auto-resolve threshold, found by {@code ReconciliationServiceImpl}. */
@Entity
@Table(name = "reconciliation_alerts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationAlert {

    @Id
    private UUID alertId;

    @Column(nullable = false)
    private UUID paymentId;

    @Column(nullable = false)
    private BigDecimal expectedAmount;

    @Column(nullable = false)
    private BigDecimal actualAmount;

    @Column(nullable = false)
    private BigDecimal discrepancy;

    @Column(nullable = false)
    private Instant createdAt;
}
