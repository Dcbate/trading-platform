package com.dcbate.tradingplatform.transfer.service;

import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.transfer.api.dto.TransferRequest;
import com.dcbate.tradingplatform.transfer.api.dto.TransferResponse;
import java.util.UUID;

/**
 * Same-bank ("pay other users") transfers. Unlike {@code PaymentService} (cross-bank), a transfer
 * is a single atomic debit+credit within our own database — no saga, no external clearing.
 */
public interface TransferService {

    TransferResponse transfer(TransferRequest request, CallerPrincipal caller);

    TransferResponse getTransfer(UUID transferId, CallerPrincipal caller);
}
