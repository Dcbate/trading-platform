package com.dcbate.tradingplatform.account.repository;

import com.dcbate.tradingplatform.domain.AccountActivity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code findByClientIdOrderByOccurredAtDesc} backs the bank statement. */
public interface AccountActivityRepository extends JpaRepository<AccountActivity, UUID> {

    List<AccountActivity> findByClientIdOrderByOccurredAtDesc(String clientId);
}
