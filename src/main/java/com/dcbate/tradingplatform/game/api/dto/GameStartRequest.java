package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code clientId} is explicit here rather than read off {@code CallerPrincipal} the way session
 * actions are — every other client-scoped write in the app (open account, originate loan, send
 * transfer) takes {@code clientId} explicitly and checks {@code caller.requireOwner(clientId)},
 * so a session's owner is determined the same way instead of trusting whatever identity the
 * current security profile happens to resolve the caller to.
 */
public record GameStartRequest(@NotBlank String clientId, @NotNull GameDifficulty difficulty) {
}
