package com.dcbate.tradingplatform.chronicle;

import com.dcbate.tradingplatform.kafka.event.TradeEvent;

public interface TradeJournalWriter {

    void append(TradeEvent trade);
}
