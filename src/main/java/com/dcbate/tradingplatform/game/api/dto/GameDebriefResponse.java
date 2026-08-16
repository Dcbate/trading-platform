package com.dcbate.tradingplatform.game.api.dto;

import java.util.List;

/**
 * {@code aiGenerated=false} means Claude wasn't available (no key configured, or the call
 * failed/timed out) — {@code summary} is then the plain rule-based paragraph computed from the
 * same {@code symbolPerformance} data instead, not a placeholder.
 */
public record GameDebriefResponse(String summary, boolean aiGenerated, List<GameSymbolPerformanceResponse> symbolPerformance) {
}
