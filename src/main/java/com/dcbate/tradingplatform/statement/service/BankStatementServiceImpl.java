package com.dcbate.tradingplatform.statement.service;

import com.dcbate.tradingplatform.account.repository.AccountActivityRepository;
import com.dcbate.tradingplatform.account.repository.AccountRepository;
import com.dcbate.tradingplatform.domain.Account;
import com.dcbate.tradingplatform.domain.AccountActivity;
import com.dcbate.tradingplatform.domain.LoanActivity;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.Transfer;
import com.dcbate.tradingplatform.loan.repository.LoanActivityRepository;
import com.dcbate.tradingplatform.payment.repository.PaymentRepository;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.statement.api.dto.BankStatementEntry;
import com.dcbate.tradingplatform.statement.api.dto.BankStatementResponse;
import com.dcbate.tradingplatform.statement.api.dto.StatementEntryType;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import com.dcbate.tradingplatform.transfer.repository.TransferRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merges every domain that moves a client's money — or sits on the FX desk — into one
 * chronological feed: {@link Order}s, {@link Payment}s, {@link Transfer}s,
 * {@link AccountActivity} (deposit/withdraw/convert/close), and {@link LoanActivity}
 * (origination/repayment). Each source already has its own ownership-checked endpoint; this
 * service doesn't replace those, it's the unified read a client actually wants — "everything
 * that happened," not five separate screens.
 */
@Service
@RequiredArgsConstructor
public class BankStatementServiceImpl implements BankStatementService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final TransferRepository transferRepository;
    private final AccountActivityRepository accountActivityRepository;
    private final LoanActivityRepository loanActivityRepository;
    private final AccountRepository accountRepository;

    @Override
    @Transactional(readOnly = true)
    public BankStatementResponse getStatement(String clientId, CallerPrincipal caller) {
        caller.requireOwner(clientId);

        Map<UUID, Account> accountsById = accountRepository.findByClientId(clientId).stream()
                .collect(java.util.stream.Collectors.toMap(Account::getAccountId, Function.identity()));

        List<BankStatementEntry> entries = new ArrayList<>();
        orderRepository.findByClientIdOrderByCreatedAtDesc(clientId).forEach(o -> entries.add(orderEntry(o)));
        paymentRepository.findByClientIdOrderByCreatedAtDesc(clientId).forEach(p -> entries.add(paymentEntry(p)));
        transferRepository.findByFromClientIdOrToClientIdOrderByCreatedAtDesc(clientId, clientId)
                .forEach(t -> entries.addAll(transferEntries(t, clientId)));
        accountActivityRepository.findByClientIdOrderByOccurredAtDesc(clientId)
                .forEach(a -> entries.add(accountActivityEntry(a, accountsById)));
        loanActivityRepository.findByClientIdOrderByOccurredAtDesc(clientId)
                .forEach(l -> entries.add(loanActivityEntry(l)));

        entries.sort(Comparator.comparing(BankStatementEntry::occurredAt).reversed());
        return new BankStatementResponse(clientId, entries);
    }

    private BankStatementEntry orderEntry(Order order) {
        String description = "%s %s %s @ %s — %s".formatted(
                order.getSide(), order.getQuantity().stripTrailingZeros().toPlainString(),
                order.getCurrencyPair(), order.getPrice(), order.getStatus());
        return new BankStatementEntry(order.getCreatedAt(), StatementEntryType.FX_ORDER, description, null, order.getOrderId());
    }

    private BankStatementEntry paymentEntry(Payment payment) {
        String description = "Payment to a bank in %s — %s".formatted(payment.getCountry(), payment.getStatus());
        return new BankStatementEntry(
                payment.getCreatedAt(), StatementEntryType.PAYMENT, description, payment.getAmount().negate(), payment.getPaymentId());
    }

    private List<BankStatementEntry> transferEntries(Transfer transfer, String clientId) {
        List<BankStatementEntry> result = new ArrayList<>();
        if (transfer.getFromClientId().equals(clientId)) {
            String description = "Transfer to client %s — %s".formatted(shortId(transfer.getToClientId()), transfer.getStatus());
            result.add(new BankStatementEntry(
                    transfer.getCreatedAt(), StatementEntryType.TRANSFER_OUT, description,
                    transfer.getAmount().negate(), transfer.getTransferId()));
        }
        if (transfer.getToClientId().equals(clientId)) {
            String description = "Transfer from client %s — %s".formatted(shortId(transfer.getFromClientId()), transfer.getStatus());
            result.add(new BankStatementEntry(
                    transfer.getCreatedAt(), StatementEntryType.TRANSFER_IN, description,
                    transfer.getAmount(), transfer.getTransferId()));
        }
        return result;
    }

    private BankStatementEntry accountActivityEntry(AccountActivity activity, Map<UUID, Account> accountsById) {
        String accountLabel = accountLabel(accountsById.get(activity.getAccountId()));
        return switch (activity.getType()) {
            case DEPOSIT -> new BankStatementEntry(
                    activity.getOccurredAt(), StatementEntryType.DEPOSIT, "Deposit into " + accountLabel,
                    activity.getAmount(), activity.getActivityId());
            case WITHDRAWAL -> new BankStatementEntry(
                    activity.getOccurredAt(), StatementEntryType.WITHDRAWAL, "Withdrawal from " + accountLabel,
                    activity.getAmount().negate(), activity.getActivityId());
            case CONVERSION -> new BankStatementEntry(
                    activity.getOccurredAt(), StatementEntryType.CONVERSION,
                    "Converted from %s to %s at rate %s".formatted(
                            accountLabel, accountLabel(accountsById.get(activity.getRelatedAccountId())), activity.getRate()),
                    activity.getAmount().negate(), activity.getActivityId());
            case CLOSURE -> new BankStatementEntry(
                    activity.getOccurredAt(), StatementEntryType.ACCOUNT_CLOSURE,
                    "Closed %s, balance swept to %s".formatted(
                            accountLabel, accountLabel(accountsById.get(activity.getRelatedAccountId()))),
                    activity.getAmount().negate(), activity.getActivityId());
        };
    }

    private BankStatementEntry loanActivityEntry(LoanActivity activity) {
        return switch (activity.getType()) {
            case ORIGINATED -> new BankStatementEntry(
                    activity.getOccurredAt(), StatementEntryType.LOAN_ORIGINATED, "Loan originated",
                    activity.getAmount(), activity.getActivityId());
            case REPAYMENT -> new BankStatementEntry(
                    activity.getOccurredAt(), StatementEntryType.LOAN_REPAYMENT,
                    "Loan repayment — %s outstanding".formatted(activity.getOutstandingPrincipal()),
                    activity.getAmount().negate(), activity.getActivityId());
        };
    }

    private String accountLabel(Account account) {
        if (account == null) {
            return "another account";
        }
        return account.getNickname() != null ? account.getNickname() : account.getAccountType() + " " + account.getCurrency();
    }

    private String shortId(String clientId) {
        return clientId.length() > 8 ? clientId.substring(0, 8) : clientId;
    }
}
