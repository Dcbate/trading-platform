package com.dcbate.tradingplatform.payment.repository;

import com.dcbate.tradingplatform.domain.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByPaymentId(UUID paymentId);
}
