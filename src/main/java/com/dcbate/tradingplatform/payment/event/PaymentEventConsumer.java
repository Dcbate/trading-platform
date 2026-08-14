package com.dcbate.tradingplatform.payment.event;

import com.dcbate.tradingplatform.kafka.event.PaymentEvent;
import com.dcbate.tradingplatform.payment.service.FraudDetectionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Batch-consumes {@code payments} and hands each event to the Fraud Detection Service. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final FraudDetectionService fraudDetectionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${kafka.topics.payments}",
            groupId = "fraud-detection-service",
            containerFactory = "batchListenerFactory")
    public void onPayments(List<String> records) {
        records.parallelStream().forEach(this::processRecord);
    }

    private void processRecord(String payload) {
        try {
            fraudDetectionService.evaluate(objectMapper.readValue(payload, PaymentEvent.class));
        } catch (Exception e) {
            log.error("Failed to process payment event, skipping: {}", e.getMessage());
        }
    }
}
