package com.dcbate.tradingplatform.statement.service;

import com.dcbate.tradingplatform.activity.repository.ActivityRepository;
import com.dcbate.tradingplatform.domain.Activity;
import com.dcbate.tradingplatform.domain.ActivityType;
import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.payment.repository.PaymentRepository;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.statement.api.dto.BankStatementEntry;
import com.dcbate.tradingplatform.statement.api.dto.BankStatementResponse;
import com.dcbate.tradingplatform.trading.repository.OrderRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merges every domain that moves a client's money — or sits on the FX desk — into one
 * chronological feed. Almost all of it comes from exactly one place, {@link ActivityRepository}:
 * every deposit, withdrawal, conversion, account closure, loan origination/repayment, and transfer
 * leg is written there, once, at the moment it happens (see {@code AccountServiceImpl},
 * {@code LoanServiceImpl}, {@code TransferServiceImpl}, and {@code ActivityEventListener}) —
 * this service does no enrichment, it reads rows back verbatim.
 *
 * <p>{@link Order} and {@link Payment} are the two exceptions, read directly from their own
 * tables rather than mirrored into {@code Activity}. Both are aggregate roots with a genuinely
 * evolving lifecycle owned by several other services over time (Risk Service and the Matching
 * Engine for an order; Fraud Detection, Settlement, and Reconciliation for a payment) — they're
 * not one-shot events the way a deposit or a transfer leg is, so there's no single "point of
 * cause" to mirror them from without either duplicating that whole lifecycle into the audit trail
 * or letting the mirrored copy silently drift out of sync with the real row. Reading them directly
 * keeps them precise, at the cost of this service needing two repositories instead of one.
 */
@Service
@RequiredArgsConstructor
public class BankStatementServiceImpl implements BankStatementService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ActivityRepository activityRepository;

    @Override
    @Transactional(readOnly = true)
    public BankStatementResponse getStatement(String clientId, UUID accountId, CallerPrincipal caller) {
        caller.requireOwner(clientId);

        List<BankStatementEntry> entries = new ArrayList<>();
        orderRepository.findByClientIdOrderByCreatedAtDesc(clientId).stream()
                .filter(o -> accountId == null || accountId.equals(o.getAccountId()))
                .forEach(o -> entries.add(orderEntry(o)));
        paymentRepository.findByClientIdOrderByCreatedAtDesc(clientId).stream()
                .filter(p -> accountId == null || accountId.equals(p.getSourceAccountId()))
                .forEach(p -> entries.add(paymentEntry(p)));
        activities(clientId, accountId).forEach(a -> entries.add(activityEntry(a)));

        entries.sort(Comparator.comparing(BankStatementEntry::occurredAt).reversed());
        return new BankStatementResponse(clientId, entries);
    }

    private List<Activity> activities(String clientId, UUID accountId) {
        return accountId == null
                ? activityRepository.findByClientIdOrderByOccurredAtDesc(clientId)
                : activityRepository.findByClientIdAndAccountIdOrderByOccurredAtDesc(clientId, accountId);
    }

    private BankStatementEntry orderEntry(Order order) {
        String description = "%s %s %s @ %s — %s".formatted(
                order.getSide(), order.getQuantity().stripTrailingZeros().toPlainString(),
                order.getCurrencyPair(), order.getPrice(), order.getStatus());
        return new BankStatementEntry(order.getCreatedAt(), ActivityType.FX_ORDER, description, null, null, order.getOrderId());
    }

    private BankStatementEntry paymentEntry(Payment payment) {
        String description = "Payment to a bank in %s — %s".formatted(payment.getCountry(), payment.getStatus());
        return new BankStatementEntry(payment.getCreatedAt(), ActivityType.PAYMENT, description,
                payment.getAmount().negate(), null, payment.getPaymentId());
    }

    private BankStatementEntry activityEntry(Activity activity) {
        return new BankStatementEntry(activity.getOccurredAt(), activity.getType(), activity.getDescription(),
                activity.getAmount(), activity.getCurrency(), activity.getActivityId());
    }
}
