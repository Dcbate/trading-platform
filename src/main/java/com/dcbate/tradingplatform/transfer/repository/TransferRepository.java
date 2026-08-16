package com.dcbate.tradingplatform.transfer.repository;

import com.dcbate.tradingplatform.domain.Transfer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The completed/failed {@code Transfer} row itself is the audit record — no separate ledger
 * entries, unlike {@code Payment}. {@code findByFromClientIdOrToClientIdOrderByCreatedAtDesc}
 * backs the bank statement — a client can be either side of a transfer.
 */
public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    List<Transfer> findByFromClientIdOrToClientIdOrderByCreatedAtDesc(String fromClientId, String toClientId);
}
