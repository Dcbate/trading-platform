package com.dcbate.tradingplatform.trading.api;

import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.trading.api.dto.PositionResponse;
import com.dcbate.tradingplatform.trading.service.PositionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only holdings view — what a client actually owns, built from real settled fills. */
@RestController
@RequestMapping("/v1/positions")
@RequiredArgsConstructor
@Tag(name = "Positions", description = "A client's real stock holdings")
public class PositionController {

    private final PositionService positionService;

    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN', 'AUDITOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "List a client's positions (symbol, quantity, average cost)")
    public ResponseEntity<List<PositionResponse>> listPositions(@RequestParam String clientId, Authentication authentication) {
        return ResponseEntity.ok(positionService.listPositionsForClient(clientId, CallerPrincipal.from(authentication)));
    }
}
