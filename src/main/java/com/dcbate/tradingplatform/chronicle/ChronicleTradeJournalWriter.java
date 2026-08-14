package com.dcbate.tradingplatform.chronicle;

import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import org.springframework.stereotype.Component;

/**
 * @see TradeJournalWriter
 *
 * A serialization failure is logged and dropped rather than thrown — a malformed journal entry
 * shouldn't take down the trade pipeline that's already committed the trade elsewhere (Postgres,
 * the {@code trades} topic); the journal is a compliance audit trail, not the source of truth.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChronicleTradeJournalWriter implements TradeJournalWriter {

    private final ChronicleQueue tradeJournalQueue;
    private final ObjectMapper objectMapper;

    @Override
    public void append(TradeEvent trade) {
        try {
            String payload = objectMapper.writeValueAsString(trade);
            try (ExcerptAppender appender = tradeJournalQueue.createAppender()) {
                appender.writeText(payload);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize trade {} for journal append: {}", trade.tradeId(), e.getMessage());
        }
    }
}
