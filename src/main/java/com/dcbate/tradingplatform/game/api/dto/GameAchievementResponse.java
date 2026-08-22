package com.dcbate.tradingplatform.game.api.dto;

/** One badge earned for a finished session — purely a fun, after-the-fact read of already-recorded trade/loan/session data, never a new rule that changes how a game plays out. */
public record GameAchievementResponse(String title, String description) {}
