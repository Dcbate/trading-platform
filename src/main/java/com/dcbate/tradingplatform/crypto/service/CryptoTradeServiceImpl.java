package com.dcbate.tradingplatform.crypto.service;

import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.activity.event.ActivityRecordedEvent;
import com.dcbate.tradingplatform.config.TradingProperties;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.AccountType;
import com.dcbate.tradingplatform.domain.ActivityType;
import com.dcbate.tradingplatform.domain.Position;
import com.dcbate.tradingplatform.exception.AccountNotActiveException;
import com.dcbate.tradingplatform.exception.AccountNotFoundException;
import com.dcbate.tradingplatform.exception.AccountTypeMismatchException;
import com.dcbate.tradingplatform.exception.CryptoPriceUnavailableException;
import com.dcbate.tradingplatform.exception.CurrencyMismatchException;
import com.dcbate.tradingplatform.exception.InsufficientFundsException;
import com.dcbate.tradingplatform.exception.InsufficientPositionException;
import com.dcbate.tradingplatform.exception.UnsupportedCryptoSymbolException;
import com.dcbate.tradingplatform.crypto.api.dto.CryptoTradeRequest;
import com.dcbate.tradingplatform.crypto.api.dto.CryptoTradeResponse;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.trading.repository.PositionRepository;
import com.dcbate.tradingplatform.trading.service.PriceFeedService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @see CryptoTradeService
 *
 * Deliberately does not go through {@code OrderController}/{@code RiskService}/
 * {@code MatchingEngineService} — a crypto buy would otherwise only fill once some *other*
 * client's resting sell crossed it, the same bootstrapping problem the FX "dealer desk" pattern
 * exists to paper over (see {@code docs/HOW_A_TRADE_FILLS.md}). A retail crypto trade should not
 * depend on another customer's order existing. Instead this settles instantly against the live
 * {@link PriceFeedService} price, in one transaction — the same shape as
 * {@code AccountServiceImpl.convert()} (debit one side, credit the other, no matching required),
 * just crediting a {@link Position} instead of a second account. Full reasoning in
 * {@code docs/CRYPTO.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoTradeServiceImpl implements CryptoTradeService {

    /** Every crypto pair is quoted against sterling — this is a UK bank, not a US or multi-currency exchange. */
    static final String CRYPTO_QUOTE_CURRENCY = "GBP";

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final PriceFeedService priceFeedService;
    private final TradingProperties tradingProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public CryptoTradeResponse buy(UUID accountId, CryptoTradeRequest request, CallerPrincipal caller) {
        Account account = requireCryptoAccount(accountId, request.symbol(), caller);
        BigDecimal price = currentPrice(request.symbol());
        BigDecimal notional = request.quantity().multiply(price);

        if (account.getBalance().compareTo(notional) < 0) {
            throw new InsufficientFundsException(accountId, notional, account.getBalance());
        }
        account.setBalance(account.getBalance().subtract(notional));
        Account savedAccount = accountRepository.save(account);

        Position position = requirePosition(accountId, request.symbol(), account.getClientId());
        BigDecimal newQuantity = position.getQuantity().add(request.quantity());
        BigDecimal newCostBasis = position.getQuantity().multiply(position.getAvgCost()).add(notional);
        position.setAvgCost(newQuantity.signum() == 0 ? BigDecimal.ZERO : newCostBasis.divide(newQuantity, 8, RoundingMode.HALF_UP));
        position.setQuantity(newQuantity);
        position.setUpdatedAt(Instant.now());
        Position savedPosition = positionRepository.save(position);

        recordActivity(account, ActivityType.CRYPTO_BUY, notional.negate(),
                "Bought %s %s at %s".formatted(plain(request.quantity()), request.symbol(), price));
        log.info("Crypto buy settled: accountId={}, symbol={}, quantity={}, price={}, notional={}, balanceAfter={}",
                accountId, request.symbol(), request.quantity(), price, notional, savedAccount.getBalance());

        return response(accountId, request, "BUY", price, notional, savedAccount, savedPosition);
    }

    @Override
    @Transactional
    public CryptoTradeResponse sell(UUID accountId, CryptoTradeRequest request, CallerPrincipal caller) {
        Account account = requireCryptoAccount(accountId, request.symbol(), caller);
        Position position = positionRepository.findByAccountIdAndSymbol(accountId, request.symbol())
                .orElseThrow(() -> new InsufficientPositionException(accountId, request.symbol(), request.quantity(), BigDecimal.ZERO));
        if (position.getQuantity().compareTo(request.quantity()) < 0) {
            throw new InsufficientPositionException(accountId, request.symbol(), request.quantity(), position.getQuantity());
        }
        BigDecimal price = currentPrice(request.symbol());
        BigDecimal notional = request.quantity().multiply(price);

        account.setBalance(account.getBalance().add(notional));
        Account savedAccount = accountRepository.save(account);

        // avgCost is deliberately untouched on a sell — same convention as ExecutionServiceImpl.
        position.setQuantity(position.getQuantity().subtract(request.quantity()));
        position.setUpdatedAt(Instant.now());
        Position savedPosition = positionRepository.save(position);

        recordActivity(account, ActivityType.CRYPTO_SELL, notional,
                "Sold %s %s at %s".formatted(plain(request.quantity()), request.symbol(), price));
        log.info("Crypto sell settled: accountId={}, symbol={}, quantity={}, price={}, notional={}, balanceAfter={}",
                accountId, request.symbol(), request.quantity(), price, notional, savedAccount.getBalance());

        return response(accountId, request, "SELL", price, notional, savedAccount, savedPosition);
    }

    private Account requireCryptoAccount(UUID accountId, String symbol, CallerPrincipal caller) {
        if (!tradingProperties.cryptoSymbols().contains(symbol)) {
            throw new UnsupportedCryptoSymbolException(symbol);
        }
        Account account = accountRepository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        caller.requireOwner(account.getClientId());
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(accountId);
        }
        if (account.getAccountType() != AccountType.CRYPTO) {
            throw new AccountTypeMismatchException(accountId, AccountType.CRYPTO, account.getAccountType());
        }
        if (!account.getCurrency().equals(CRYPTO_QUOTE_CURRENCY)) {
            throw new CurrencyMismatchException(account.getCurrency(), CRYPTO_QUOTE_CURRENCY);
        }
        return account;
    }

    private Position requirePosition(UUID accountId, String symbol, String clientId) {
        return positionRepository.findByAccountIdAndSymbol(accountId, symbol)
                .orElseGet(() -> Position.builder()
                        .positionId(UUID.randomUUID())
                        .accountId(accountId)
                        .clientId(clientId)
                        .symbol(symbol)
                        .quantity(BigDecimal.ZERO)
                        .avgCost(BigDecimal.ZERO)
                        .updatedAt(Instant.now())
                        .build());
    }

    private BigDecimal currentPrice(String symbol) {
        return priceFeedService.currentPrice(symbol).orElseThrow(() -> new CryptoPriceUnavailableException(symbol));
    }

    private void recordActivity(Account account, ActivityType type, BigDecimal signedAmount, String description) {
        eventPublisher.publishEvent(new ActivityRecordedEvent(
                account.getClientId(), account.getAccountId(), type, signedAmount, account.getCurrency(), description));
    }

    private CryptoTradeResponse response(UUID accountId, CryptoTradeRequest request, String side, BigDecimal price,
            BigDecimal notional, Account savedAccount, Position savedPosition) {
        return new CryptoTradeResponse(accountId, request.symbol(), side, request.quantity(), price, notional,
                savedAccount.getBalance(), savedPosition.getQuantity(), Instant.now());
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
