package com.dcbate.tradingplatform.auth.api.dto;

/**
 * The tokens themselves are never in this body — they're set as HTTP-only cookies (see
 * {@code AuthController}) so client-side JavaScript can never read them, which is the whole point
 * of not using localStorage. This just tells the frontend who's now logged in.
 */
public record AuthResponse(String clientId, String email) {
}
