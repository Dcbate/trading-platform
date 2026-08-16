package com.dcbate.tradingplatform.ai;

/**
 * Turns a finished Game Mode session's trade/loan history into a short coaching debrief — purely
 * advisory, same pattern as {@link AnomalyDetector} and {@link ClaudeSummarizer}: the game has
 * already been won or lost before this is called, so a missing/unavailable API key never blocks
 * that outcome, only the write-up of it.
 */
public interface GameCoach {

    GameDebriefResult debrief(GameDebriefContext context);
}
