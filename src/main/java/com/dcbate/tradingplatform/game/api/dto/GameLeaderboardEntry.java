package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.domain.GameStatus;
import java.math.BigDecimal;

/**
 * One row on a difficulty's leaderboard. Deliberately carries no client identifier — a public
 * leaderboard showing who's behind a score would undo the same "no ids in front of other clients"
 * principle the rest of the UI follows, so {@code mine} is the only thing tying a row back to the
 * caller, computed server-side from the caller's own clientId rather than exposed to them.
 *
 * <p>{@code durationSeconds} is always present regardless of {@link GameLeaderboardSortBy} — every
 * session that ever qualifies for a leaderboard row has both a {@code startedAt} and a
 * {@code finishedAt} — so a net-worth-sorted board can still show how long a run took, and a
 * fastest-win board can still show what it was worth.
 */
public record GameLeaderboardEntry(int rank, BigDecimal netWorth, long durationSeconds, GameStatus status, boolean mine) {}
