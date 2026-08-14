package com.dcbate.tradingplatform.transfer.api;

import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.transfer.api.dto.TransferRequest;
import com.dcbate.tradingplatform.transfer.api.dto.TransferResponse;
import com.dcbate.tradingplatform.transfer.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST surface for "pay other users at this bank" — see {@code PaymentController} for cross-bank payments. */
@Slf4j
@RestController
@RequestMapping("/v1/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers", description = "Same-bank transfers between clients")
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Transfer money to another client's account at this bank")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request, Authentication authentication) {
        TransferResponse response = transferService.transfer(request, CallerPrincipal.from(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{transferId}")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN', 'AUDITOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "Look up a transfer by id")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable UUID transferId, Authentication authentication) {
        return ResponseEntity.ok(transferService.getTransfer(transferId, CallerPrincipal.from(authentication)));
    }
}
