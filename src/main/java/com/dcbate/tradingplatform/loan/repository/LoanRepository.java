package com.dcbate.tradingplatform.loan.repository;

import com.dcbate.tradingplatform.domain.Loan;
import com.dcbate.tradingplatform.domain.LoanStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code findByStatus(ACTIVE)} is what {@code LoanServiceImpl.accrueInterest()}'s scheduled job iterates each run. */
public interface LoanRepository extends JpaRepository<Loan, UUID> {

    List<Loan> findByClientId(String clientId);

    List<Loan> findByStatus(LoanStatus status);
}
