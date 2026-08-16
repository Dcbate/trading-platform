package com.dcbate.tradingplatform.ai;

/**
 * Enriches a rule-based anomaly reason with an AI-written explanation — purely advisory. The
 * rule that flagged the anomaly (a price spike, a fraud rule) already decided the outcome before
 * this is called; a missing/unavailable API key never blocks that decision, only the narration.
 * See {@code AnomalyDetectorImpl} for the real implementation and its fallback behavior.
 */
public interface AnomalyDetector {

    AnomalyResult explain(AnomalyContext context);
}
