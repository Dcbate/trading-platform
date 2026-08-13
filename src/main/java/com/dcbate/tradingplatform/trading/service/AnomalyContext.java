package com.dcbate.tradingplatform.trading.service;

/** A threshold rule has already fired; this is the raw context handed to the AI for enrichment. */
public record AnomalyContext(String subject, String description) {
}
