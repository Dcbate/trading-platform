package com.dcbate.tradingplatform.chronicle;

import com.dcbate.tradingplatform.kafka.event.TradeEvent;

/** Appends a trade to the off-heap compliance journal — see {@code TradeJournalWriterImpl}. */
public interface TradeJournalWriter {

    void append(TradeEvent trade);
}
