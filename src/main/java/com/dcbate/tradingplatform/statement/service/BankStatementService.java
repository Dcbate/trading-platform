package com.dcbate.tradingplatform.statement.service;

import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.statement.api.dto.BankStatementResponse;
import java.util.UUID;

public interface BankStatementService {

    /** {@code accountId} is optional — {@code null} returns every account, a specific id scopes the feed to just that one. */
    BankStatementResponse getStatement(String clientId, UUID accountId, CallerPrincipal caller);
}
