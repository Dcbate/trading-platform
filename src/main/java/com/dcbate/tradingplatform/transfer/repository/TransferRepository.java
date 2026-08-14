package com.dcbate.tradingplatform.transfer.repository;

import com.dcbate.tradingplatform.domain.Transfer;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The completed/failed {@code Transfer} row itself is the audit record — no separate ledger entries, unlike {@code Payment}. */
public interface TransferRepository extends JpaRepository<Transfer, UUID> {
}
