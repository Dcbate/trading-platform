package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.trading.api.dto.PositionResponse;
import java.util.List;

/** Read-only view of what a client holds — written only by {@code ExecutionServiceImpl} as stock order fills settle. */
public interface PositionService {

    List<PositionResponse> listPositionsForClient(String clientId, CallerPrincipal caller);
}
