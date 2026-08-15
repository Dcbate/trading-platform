package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import jakarta.validation.constraints.NotNull;

public record GameStartRequest(@NotNull GameDifficulty difficulty) {
}
