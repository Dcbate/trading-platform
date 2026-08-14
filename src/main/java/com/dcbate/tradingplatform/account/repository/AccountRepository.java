package com.dcbate.tradingplatform.account.repository;

import com.dcbate.tradingplatform.domain.Account;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code findByClientId} backs the "list a client's accounts" endpoint and every ownership-check lookup across the payment/transfer/loan services. */
public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByClientId(String clientId);
}
