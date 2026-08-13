package com.dcbate.tradingplatform.ai;

public interface AnomalyDetector {

    AnomalyResult explain(AnomalyContext context);
}
