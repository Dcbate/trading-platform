package com.dcbate.tradingplatform.trading.api;

import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.trading.api.dto.OrderRequest;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import com.dcbate.tradingplatform.trading.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for order submission and lookup — both the FX desk's dealer-submitted orders
 * ({@code TRADER}/{@code ADMIN}, no funding account, no settlement) and a retail client's stock
 * orders ({@code CLIENT}, carries an {@code accountId}, settled for real on fill by
 * {@code ExecutionServiceImpl}). Every method resolves a {@link CallerPrincipal} and enforces
 * that a non-staff caller can only act on their own orders — {@code TRADER}/{@code ADMIN} count
 * as staff (see {@link CallerPrincipal}), so the dealer-desk workflow is unaffected.
 */
@Slf4j
@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Order submission and lookup — FX dealer desk and client stock orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @PreAuthorize("hasAnyRole('TRADER', 'CLIENT', 'ADMIN')")
    @Operation(summary = "Submit a buy or sell order")
    public ResponseEntity<OrderResponse> submitOrder(@Valid @RequestBody OrderRequest request, Authentication authentication) {
        OrderResponse response = orderService.submitOrder(request, CallerPrincipal.from(authentication));
        return ResponseEntity.created(URI.create("/v1/orders/" + response.orderId())).body(response);
    }

    // The UUID-shaped constraint isn't just documentation: without it, this pattern also matches
    // literal path segments like "stream", which shadows WebSocketConfig's /v1/orders/stream
    // handler — RequestMappingHandlerMapping is checked before the WebSocket handler mapping, so
    // an unconstrained {orderId} here silently swallows every WebSocket upgrade attempt and routes
    // it to this method instead, which then fails trying to parse "stream" as a UUID.
    @GetMapping("/{orderId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")
    @PreAuthorize("hasAnyRole('TRADER', 'CLIENT', 'ADMIN', 'AUDITOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "Look up an order by id")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID orderId, Authentication authentication) {
        return ResponseEntity.ok(orderService.getOrder(orderId, CallerPrincipal.from(authentication)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TRADER', 'CLIENT', 'ADMIN', 'AUDITOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "List a client's orders, most recent first")
    public ResponseEntity<List<OrderResponse>> listOrders(@RequestParam String clientId, Authentication authentication) {
        return ResponseEntity.ok(orderService.listOrdersForClient(clientId, CallerPrincipal.from(authentication)));
    }
}
