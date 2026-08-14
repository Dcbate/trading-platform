package com.dcbate.tradingplatform.transfer.api.dto;

import com.dcbate.tradingplatform.domain.Transfer;
import com.dcbate.tradingplatform.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        String fromClientId,
        String toClientId,
        BigDecimal amount,
        TransferStatus status,
        Instant createdAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getTransferId(),
                transfer.getFromAccountId(),
                transfer.getToAccountId(),
                transfer.getFromClientId(),
                transfer.getToClientId(),
                transfer.getAmount(),
                transfer.getStatus(),
                transfer.getCreatedAt());
    }
}
