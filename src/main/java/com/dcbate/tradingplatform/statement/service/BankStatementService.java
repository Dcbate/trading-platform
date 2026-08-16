package com.dcbate.tradingplatform.statement.service;

import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.statement.api.dto.BankStatementResponse;

public interface BankStatementService {

    BankStatementResponse getStatement(String clientId, CallerPrincipal caller);
}
