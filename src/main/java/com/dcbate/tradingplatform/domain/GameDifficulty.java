package com.dcbate.tradingplatform.domain;

import java.math.BigDecimal;
import lombok.Getter;

/**
 * Game Mode's fixed difficulty tiers — its own economy, entirely separate from the real accounts
 * the rest of the app manages. There's no leverage/margin-call field here on purpose: a loan
 * already lets a player trade with more than their starting cash, which is the same effect a
 * literal leverage multiplier would have, just without a second liquidation-math system to build
 * and explain. {@code chaosMode} is the one difficulty-specific market behavior that couldn't be
 * expressed as "more volatility": Rogue mode's occasional outsized price jump, on top of its
 * already-wide normal moves.
 */
@Getter
public enum GameDifficulty {
    APPRENTICE("Apprentice", new BigDecimal("10000"), new BigDecimal("1000"), 15,
            new BigDecimal("5.00"), new BigDecimal("0.000"), 0.01, 0.02, false),
    TRADER("Trader", new BigDecimal("50000"), new BigDecimal("5000"), 20,
            new BigDecimal("8.00"), new BigDecimal("0.001"), 0.025, 0.04, false),
    MAVERICK("Maverick", new BigDecimal("100000"), new BigDecimal("2000"), 25,
            new BigDecimal("12.00"), new BigDecimal("0.0025"), 0.04, 0.065, false),
    ROGUE("Rogue", new BigDecimal("500000"), new BigDecimal("500"), 30,
            new BigDecimal("20.00"), new BigDecimal("0.01"), 0.075, 0.115, true);

    private final String displayName;
    private final BigDecimal goalAmount;
    private final BigDecimal startingCash;
    private final int durationMinutes;
    private final BigDecimal loanRateAnnualPercent;
    private final BigDecimal feeRate;
    private final double fxVolatility;
    private final double stockVolatility;
    private final boolean chaosMode;

    GameDifficulty(String displayName, BigDecimal goalAmount, BigDecimal startingCash, int durationMinutes,
            BigDecimal loanRateAnnualPercent, BigDecimal feeRate, double fxVolatility, double stockVolatility, boolean chaosMode) {
        this.displayName = displayName;
        this.goalAmount = goalAmount;
        this.startingCash = startingCash;
        this.durationMinutes = durationMinutes;
        this.loanRateAnnualPercent = loanRateAnnualPercent;
        this.feeRate = feeRate;
        this.fxVolatility = fxVolatility;
        this.stockVolatility = stockVolatility;
        this.chaosMode = chaosMode;
    }
}
