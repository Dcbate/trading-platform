package com.dcbate.tradingplatform.exception;

import java.time.Instant;
import java.util.List;

/** Uniform error body written by every {@code GlobalExceptionHandler} method. */
public record ApiError(Instant timestamp, int status, String error, List<String> messages, String path) {
}
