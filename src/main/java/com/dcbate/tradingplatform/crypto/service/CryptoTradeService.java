package com.dcbate.tradingplatform.crypto.service;

import com.dcbate.tradingplatform.crypto.api.dto.CryptoTradeRequest;
import com.dcbate.tradingplatform.crypto.api.dto.CryptoTradeResponse;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import java.util.UUID;

public interface CryptoTradeService {

    CryptoTradeResponse buy(UUID accountId, CryptoTradeRequest request, CallerPrincipal caller);

    CryptoTradeResponse sell(UUID accountId, CryptoTradeRequest request, CallerPrincipal caller);
}
