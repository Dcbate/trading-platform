package com.dcbate.tradingplatform.payment.event;

import com.dcbate.tradingplatform.payment.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final ReconciliationService reconciliationService;

    @Scheduled(cron = "${payment.reconciliation.schedule-cron}")
    public void run() {
        try {
            reconciliationService.reconcile();
        } catch (Exception e) {
            log.error("Reconciliation run failed: {}", e.getMessage(), e);
        }
    }
}
