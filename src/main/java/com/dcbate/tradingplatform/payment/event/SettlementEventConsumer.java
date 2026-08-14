package com.dcbate.tradingplatform.payment.event;

import com.dcbate.tradingplatform.kafka.event.PaymentValidatedEvent;
import com.dcbate.tradingplatform.payment.service.SettlementService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Batch-consumes {@code payments-validated} and hands each event to the Settlement Service. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementEventConsumer {

    private final SettlementService settlementService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${kafka.topics.payments-validated}",
            groupId = "settlement-service",
            containerFactory = "batchListenerFactory")
    public void onPaymentsValidated(List<String> records) {
        records.parallelStream().forEach(this::processRecord);
    }

    private void processRecord(String payload) {
        try {
            settlementService.process(objectMapper.readValue(payload, PaymentValidatedEvent.class));
        } catch (Exception e) {
            log.error("Failed to process payment-validated event, skipping: {}", e.getMessage());
        }
    }
}
