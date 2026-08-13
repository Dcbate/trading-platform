package com.dcbate.tradingplatform.trading.service;

public interface AnomalyDetector {

    AnomalyResult explain(AnomalyContext context);
}
