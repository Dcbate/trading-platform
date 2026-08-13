package com.dcbate.tradingplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "fraud_flags")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudFlag {

    @Id
    private UUID flagId;

    @Column(nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskLevel riskLevel;

    @Column(nullable = false, length = 1024)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FraudAction actionTaken;

    @Column(nullable = false)
    private Instant createdAt;
}
