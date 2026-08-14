package com.dcbate.tradingplatform.chronicle;

import com.dcbate.tradingplatform.kafka.event.TradeEvent;

/** Appends a trade to the off-heap compliance journal — see {@code ChronicleTradeJournalWriter}. */
public interface TradeJournalWriter {

    void append(TradeEvent trade);
}
