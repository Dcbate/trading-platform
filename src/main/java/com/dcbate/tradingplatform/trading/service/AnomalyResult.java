package com.dcbate.tradingplatform.trading.service;

/**
 * {@code aiEnriched=false} means the Gemini call failed or was skipped (e.g. no API key
 * configured) — {@code explanation} then falls back to the plain rule description. The threshold
 * rule that triggered the check has already run by this point either way, so trading correctness
 * never depends on this call succeeding.
 */
public record AnomalyResult(String explanation, boolean aiEnriched) {
}
