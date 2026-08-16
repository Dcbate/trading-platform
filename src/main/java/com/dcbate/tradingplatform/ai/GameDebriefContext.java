package com.dcbate.tradingplatform.ai;

/**
 * A finished Game Mode session has already been scored by the time this is built — win/loss,
 * final net worth, every trade and loan. {@code narrative} is the full write-up handed to the AI
 * for enrichment; {@code fallbackSummary} is a plain rule-based paragraph computed from the same
 * data, used unchanged if the API is unavailable.
 */
public record GameDebriefContext(String narrative, String fallbackSummary) {
}
