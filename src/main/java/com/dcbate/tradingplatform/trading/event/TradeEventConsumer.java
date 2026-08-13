package com.dcbate.tradingplatform.trading.event;

import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import com.dcbate.tradingplatform.trading.service.ExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Batch-consumes {@code trades} and hands each fill to the Execution Service. Processed
 * sequentially (not in parallel like {@link OrderEventConsumer}) because a batch can contain
 * multiple fills against the same order, and status updates for one order must apply in order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeEventConsumer {

    private final ExecutionService executionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${kafka.topics.trades}",
            groupId = "execution-service",
            containerFactory = "batchListenerFactory")
    public void onTrades(List<String> records) {
        records.forEach(this::processRecord);
    }

    private void processRecord(String payload) {
        try {
            executionService.recordTrade(objectMapper.readValue(payload, TradeEvent.class));
        } catch (Exception e) {
            log.error("Failed to process trade event, skipping: {}", e.getMessage());
        }
    }
}
