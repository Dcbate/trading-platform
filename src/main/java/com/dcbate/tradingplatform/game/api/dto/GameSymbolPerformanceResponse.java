package com.dcbate.tradingplatform.game.api.dto;

import java.math.BigDecimal;

/**
 * One symbol's full P&L for a session — {@code realizedPnl} from closed (sold) trades,
 * {@code unrealizedPnl} from whatever's still held ({@code quantityHeld > 0}). A symbol that was
 * bought and fully sold has {@code quantityHeld=0} and a purely realized {@code totalPnl}.
 */
public record GameSymbolPerformanceResponse(
        String symbol, BigDecimal realizedPnl, BigDecimal unrealizedPnl, BigDecimal totalPnl, BigDecimal quantityHeld) {
}
