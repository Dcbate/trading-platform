package com.dcbate.tradingplatform.ai;

/** A threshold rule has already fired; this is the raw context handed to the AI for enrichment. */
public record AnomalyContext(String subject, String description) {
}
