package com.dcbate.tradingplatform.transfer.repository;

import com.dcbate.tradingplatform.domain.Transfer;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {
}
