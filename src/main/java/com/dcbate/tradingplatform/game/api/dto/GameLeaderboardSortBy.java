package com.dcbate.tradingplatform.game.api.dto;

/**
 * How a Game Mode leaderboard is ranked. {@code NET_WORTH} includes every finished session
 * (won or lost — a good near-miss still shows up); {@code FASTEST_WIN} only ever includes
 * {@code WON} sessions, since "fastest loss" isn't a meaningful stat to rank by.
 */
public enum GameLeaderboardSortBy {
    NET_WORTH,
    FASTEST_WIN
}
