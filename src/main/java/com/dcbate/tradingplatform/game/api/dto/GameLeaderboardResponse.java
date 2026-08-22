package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import java.util.List;

public record GameLeaderboardResponse(GameDifficulty difficulty, GameLeaderboardSortBy sortBy, List<GameLeaderboardEntry> entries) {}
