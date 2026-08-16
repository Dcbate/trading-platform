package com.dcbate.tradingplatform.game.service;

import com.dcbate.tradingplatform.game.api.dto.GameSessionResponse;

/**
 * {@code startSession} is a "create-or-resume" operation — {@code created=false} means an
 * existing in-progress session was returned unchanged, {@code created=true} means a brand-new
 * session was persisted. {@code GameController} uses this to pick 200 vs. 201; the wire response
 * body is {@code session} either way, this wrapper never leaves the service layer.
 */
public record GameSessionStartResult(GameSessionResponse session, boolean created) {
}
