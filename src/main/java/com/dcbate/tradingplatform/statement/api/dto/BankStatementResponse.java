package com.dcbate.tradingplatform.statement.api.dto;

import java.util.List;

public record BankStatementResponse(String clientId, List<BankStatementEntry> entries) {
}
