package com.dcbate.tradingplatform.activity.repository;

import com.dcbate.tradingplatform.domain.Activity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code findByClientId*} backs the bank statement — see {@code BankStatementServiceImpl}. */
public interface ActivityRepository extends JpaRepository<Activity, UUID> {

    List<Activity> findByClientIdOrderByOccurredAtDesc(String clientId);

    List<Activity> findByClientIdAndAccountIdOrderByOccurredAtDesc(String clientId, UUID accountId);
}
