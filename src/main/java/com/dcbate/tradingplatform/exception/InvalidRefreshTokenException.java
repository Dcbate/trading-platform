package com.dcbate.tradingplatform.exception;

/** Thrown by {@code AuthServiceImpl.refresh} for a missing, expired, already-used, or unsigned-by-us refresh token; mapped to 401. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String reason) {
        super("Invalid refresh token: " + reason);
    }
}
