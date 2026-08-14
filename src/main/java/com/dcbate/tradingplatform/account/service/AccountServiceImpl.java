package com.dcbate.tradingplatform.account.service;

import com.dcbate.tradingplatform.account.api.dto.AccountRequest;
import com.dcbate.tradingplatform.account.api.dto.AccountResponse;
import com.dcbate.tradingplatform.account.api.dto.ConvertRequest;
import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountActivityType;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.exception.AccountNotActiveException;
import com.dcbate.tradingplatform.exception.AccountNotFoundException;
import com.dcbate.tradingplatform.exception.InsufficientFundsException;
import com.dcbate.tradingplatform.exception.RateUnavailableException;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.AccountActivityEvent;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.trading.service.PriceFeedService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** @see AccountService */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;
    private final PriceFeedService priceFeedService;

    @Override
    @Transactional
    public AccountResponse openAccount(AccountRequest request, CallerPrincipal caller) {
        caller.requireOwner(request.clientId());

        Account account = Account.builder()
                .accountId(UUID.randomUUID())
                .clientId(request.clientId())
                .accountType(request.accountType())
                .currency(request.currency())
                .balance(request.openingBalance())
                .status(AccountStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();

        Account saved = accountRepository.save(account);
        log.info("Account opened: accountId={}, clientId={}, type={}, currency={}, openingBalance={}",
                saved.getAccountId(), saved.getClientId(), saved.getAccountType(), saved.getCurrency(), saved.getBalance());

        return AccountResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId, CallerPrincipal caller) {
        Account account = requireAccount(accountId);
        caller.requireOwner(account.getClientId());
        return AccountResponse.from(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountResponse> listAccountsForClient(String clientId, CallerPrincipal caller) {
        caller.requireOwner(clientId);
        return accountRepository.findByClientId(clientId).stream().map(AccountResponse::from).toList();
    }

    @Override
    @Transactional
    public AccountResponse deposit(UUID accountId, BigDecimal amount, CallerPrincipal caller) {
        Account account = requireActiveOwnedAccount(accountId, caller);
        account.setBalance(account.getBalance().add(amount));
        Account saved = accountRepository.save(account);

        publishActivity(saved, AccountActivityType.DEPOSIT, amount, null, null);
        log.info("Deposit: accountId={}, amount={}, balanceAfter={}", accountId, amount, saved.getBalance());
        return AccountResponse.from(saved);
    }

    @Override
    @Transactional
    public AccountResponse withdraw(UUID accountId, BigDecimal amount, CallerPrincipal caller) {
        Account account = requireActiveOwnedAccount(accountId, caller);
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(accountId, amount, account.getBalance());
        }
        account.setBalance(account.getBalance().subtract(amount));
        Account saved = accountRepository.save(account);

        publishActivity(saved, AccountActivityType.WITHDRAWAL, amount, null, null);
        log.info("Withdrawal: accountId={}, amount={}, balanceAfter={}", accountId, amount, saved.getBalance());
        return AccountResponse.from(saved);
    }

    @Override
    @Transactional
    public AccountResponse convert(UUID fromAccountId, ConvertRequest request, CallerPrincipal caller) {
        Account from = requireActiveOwnedAccount(fromAccountId, caller);
        Account to = requireActiveOwnedAccount(request.toAccountId(), caller);
        BigDecimal amount = request.amount();

        if (from.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(fromAccountId, amount, from.getBalance());
        }

        BigDecimal rate = rateFor(from.getCurrency(), to.getCurrency());
        BigDecimal converted = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);

        from.setBalance(from.getBalance().subtract(amount));
        to.setBalance(to.getBalance().add(converted));
        accountRepository.save(from);
        Account savedTo = accountRepository.save(to);

        publishActivity(from, AccountActivityType.CONVERSION, amount, to.getAccountId(), rate);
        log.info("Conversion: fromAccountId={}, toAccountId={}, amount={}, rate={}, converted={}",
                fromAccountId, request.toAccountId(), amount, rate, converted);
        return AccountResponse.from(savedTo);
    }

    /** Looks up the direct pair first, then the inverse (1/rate) before giving up. */
    private BigDecimal rateFor(String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return BigDecimal.ONE;
        }
        Optional<BigDecimal> direct = priceFeedService.currentPrice(fromCurrency + "/" + toCurrency);
        if (direct.isPresent()) {
            return direct.get();
        }
        return priceFeedService.currentPrice(toCurrency + "/" + fromCurrency)
                .map(inverse -> BigDecimal.ONE.divide(inverse, new MathContext(10)))
                .orElseThrow(() -> new RateUnavailableException(fromCurrency, toCurrency));
    }

    private Account requireActiveOwnedAccount(UUID accountId, CallerPrincipal caller) {
        Account account = requireAccount(accountId);
        caller.requireOwner(account.getClientId());
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(accountId);
        }
        return account;
    }

    private Account requireAccount(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private void publishActivity(Account account, AccountActivityType type, BigDecimal amount, UUID relatedAccountId, BigDecimal rate) {
        kafkaEventPublisher.publish(
                topics.accountActivity(),
                account.getClientId(),
                new AccountActivityEvent(account.getAccountId(), account.getClientId(), type, amount,
                        account.getBalance(), relatedAccountId, rate, Instant.now()));
    }
}
