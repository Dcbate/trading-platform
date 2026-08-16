package com.dcbate.tradingplatform.loan.repository;

import com.dcbate.tradingplatform.domain.LoanActivity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code findByClientIdOrderByOccurredAtDesc} backs the bank statement. */
public interface LoanActivityRepository extends JpaRepository<LoanActivity, UUID> {

    List<LoanActivity> findByClientIdOrderByOccurredAtDesc(String clientId);
}
