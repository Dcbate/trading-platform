package com.dcbate.tradingplatform.chronicle;

import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptTailer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * @see TradeJournalReader
 *
 * Reads the whole journal front-to-back into memory each call — fine for the journal's actual
 * use (ad hoc compliance replay of a bounded local file), not built for streaming a large queue.
 * A single malformed entry is logged and skipped rather than failing the whole read.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeJournalReaderImpl implements TradeJournalReader {

    @Qualifier("tradeJournalQueue")
    private final ChronicleQueue tradeJournalQueue;
    private final ObjectMapper objectMapper;

    @Override
    public List<TradeEvent> readAll() {
        List<TradeEvent> trades = new ArrayList<>();
        ExcerptTailer tailer = tradeJournalQueue.createTailer();
        String payload;
        while ((payload = tailer.readText()) != null) {
            readEvent(payload).ifPresent(trades::add);
        }
        return trades;
    }

    private Optional<TradeEvent> readEvent(String payload) {
        try {
            return Optional.of(objectMapper.readValue(payload, TradeEvent.class));
        } catch (Exception e) {
            log.error("Failed to deserialize journal entry, skipping: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
