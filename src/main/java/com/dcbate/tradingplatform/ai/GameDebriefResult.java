package com.dcbate.tradingplatform.ai;

/**
 * {@code aiGenerated=false} means the Claude call failed or was skipped (no API key configured) —
 * {@code summary} then falls back to {@link GameDebriefContext#fallbackSummary()} unchanged. The
 * win/loss outcome and every number in the debrief were already decided by
 * {@code GameServiceImpl.evaluate} before this is ever called, so correctness never depends on
 * this call succeeding — only the quality of the narration does.
 */
public record GameDebriefResult(String summary, boolean aiGenerated) {
}
