package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown when a transfer id doesn't resolve to a row; mapped to 404 by {@code GlobalExceptionHandler}. */
public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException(UUID transferId) {
        super("Transfer not found: " + transferId);
    }
}
