package com.dcbate.tradingplatform.account.repository;

import com.dcbate.tradingplatform.domain.Account;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByClientId(String clientId);
}
