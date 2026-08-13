package com.dcbate.tradingplatform.payment.api;

import com.dcbate.tradingplatform.payment.api.dto.PaymentRequest;
import com.dcbate.tradingplatform.payment.api.dto.PaymentResponse;
import com.dcbate.tradingplatform.payment.service.FraudDetectionService;
import com.dcbate.tradingplatform.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment submission, lookup, and fraud-review approval")
public class PaymentController {

    private final PaymentService paymentService;
    private final FraudDetectionService fraudDetectionService;

    @PostMapping
    @PreAuthorize("hasAnyRole('TRADER', 'ADMIN')")
    @Operation(summary = "Submit a payment (idempotent on idempotencyKey)")
    public ResponseEntity<PaymentResponse> submitPayment(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = paymentService.submitPayment(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAnyRole('TRADER', 'ADMIN', 'AUDITOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "Look up a payment by id")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentService.getPayment(paymentId));
    }

    @PostMapping("/{paymentId}/approve")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER')")
    @Operation(summary = "Approve a payment held UNDER_REVIEW, releasing it into settlement")
    public ResponseEntity<Void> approvePayment(@PathVariable UUID paymentId) {
        fraudDetectionService.approve(paymentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{paymentId}/reject")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER')")
    @Operation(summary = "Reject a payment held UNDER_REVIEW, blocking it terminally")
    public ResponseEntity<Void> rejectPayment(@PathVariable UUID paymentId) {
        fraudDetectionService.reject(paymentId);
        return ResponseEntity.noContent().build();
    }
}
