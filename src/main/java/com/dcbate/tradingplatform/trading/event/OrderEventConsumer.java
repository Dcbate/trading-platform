package com.dcbate.tradingplatform.trading.event;

import com.dcbate.tradingplatform.kafka.event.OrderEvent;
import com.dcbate.tradingplatform.trading.service.RiskService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Batch-consumes {@code orders} and hands each event to the Risk Service (Low-Latency Pattern 3). */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final RiskService riskService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${kafka.topics.orders}",
            groupId = "risk-service",
            containerFactory = "batchListenerFactory")
    public void onOrders(List<String> records) {
        records.parallelStream().forEach(this::processRecord);
    }

    private void processRecord(String payload) {
        try {
            riskService.evaluate(objectMapper.readValue(payload, OrderEvent.class));
        } catch (Exception e) {
            log.error("Failed to process order event, skipping: {}", e.getMessage());
        }
    }
}
