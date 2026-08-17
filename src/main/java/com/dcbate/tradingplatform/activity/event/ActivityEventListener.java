package com.dcbate.tradingplatform.activity.event;

import com.dcbate.tradingplatform.activity.repository.ActivityRepository;
import com.dcbate.tradingplatform.domain.Activity;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns an {@link ActivityRecordedEvent} into a persisted {@link Activity} row, but only after
 * the business transaction that raised it has actually committed ({@code AFTER_COMMIT}) — not
 * inline inside e.g. {@code AccountServiceImpl.deposit()}'s own transaction. Two things that buys:
 *
 * <ul>
 *   <li>A rollback of the real money movement (say, a later check in the same transaction fails)
 *       can never leave an orphaned audit row behind — the event simply never fires if the
 *       transaction that published it never commits.</li>
 *   <li>A problem writing the audit row itself can't take down the money-moving transaction that
 *       already succeeded — the two are no longer coupled into one atomic unit.</li>
 * </ul>
 *
 * <p>Deliberately synchronous (no {@code @Async}) — this runs on the same thread, immediately
 * after commit, before the original request returns, so a client reading the bank statement right
 * after a deposit reliably sees it; making this asynchronous would reintroduce exactly the kind of
 * "did my activity actually get logged" uncertainty this design is trying to remove.
 *
 * <p><b>{@code Propagation.REQUIRES_NEW} is not optional here.</b> {@code AFTER_COMMIT} fires
 * while the original transaction's synchronization is still technically active (Spring hasn't
 * unbound its resources yet — that happens slightly later, in cleanup) — without
 * {@code REQUIRES_NEW}, {@link ActivityRepository#save} tries to participate in that already-
 * committed, about-to-be-discarded transaction instead of opening a genuinely new one. Hibernate
 * then defers the insert past the point where anything will ever flush it, so the row silently
 * never lands — no exception, nothing in the logs beyond this method returning normally. Found
 * live: the deposit succeeded, the balance moved, but the row never made it into {@code activity}
 * (confirmed empty via direct `SELECT count(*)`), because this annotation was missing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityEventListener {

    private final ActivityRepository activityRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onActivityRecorded(ActivityRecordedEvent event) {
        try {
            Activity saved = activityRepository.save(Activity.builder()
                    .activityId(UUID.randomUUID())
                    .clientId(event.clientId())
                    .accountId(event.accountId())
                    .type(event.type())
                    .amount(event.amount())
                    .currency(event.currency())
                    .description(event.description())
                    .occurredAt(Instant.now())
                    .build());
            log.info("Activity recorded: activityId={}, clientId={}, type={}, amount={}",
                    saved.getActivityId(), event.clientId(), event.type(), event.amount());
        } catch (RuntimeException e) {
            log.error("Failed to record activity: clientId={}, type={}", event.clientId(), event.type(), e);
            throw e;
        }
    }
}
