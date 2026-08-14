package com.dcbate.tradingplatform.trading.api;

import com.dcbate.tradingplatform.trading.api.dto.OrderRequest;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import com.dcbate.tradingplatform.trading.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the FX trading desk. Unlike the account/payment/transfer/loan controllers,
 * this doesn't run a {@code CallerPrincipal} ownership check — {@code TRADER} is a bank-staff
 * dealer role here, not a retail client acting on their own resource, so role gating alone is the
 * right check (see {@code docs/TRADING_SYSTEM.md}).
 */
@Slf4j
@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Order submission and lookup")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @PreAuthorize("hasRole('TRADER')")
    @Operation(summary = "Submit a buy or sell order")
    public ResponseEntity<OrderResponse> submitOrder(@Valid @RequestBody OrderRequest request) {
        OrderResponse response = orderService.submitOrder(request);
        return ResponseEntity.created(URI.create("/v1/orders/" + response.orderId())).body(response);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('TRADER', 'ADMIN', 'AUDITOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "Look up an order by id")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId));
    }
}
