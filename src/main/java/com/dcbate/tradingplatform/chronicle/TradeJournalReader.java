package com.dcbate.tradingplatform.chronicle;

import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import java.util.List;

/** Replays the immutable, append-only trade journal — used for compliance audits and recovery. */
public interface TradeJournalReader {

    List<TradeEvent> readAll();
}
