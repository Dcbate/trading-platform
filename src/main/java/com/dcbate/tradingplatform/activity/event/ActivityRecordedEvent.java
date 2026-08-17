package com.dcbate.tradingplatform.activity.event;

import com.dcbate.tradingplatform.domain.ActivityType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published by whichever service just did something to a client's money — deposit, withdrawal,
 * conversion, closure, loan origination/repayment, or one leg of a transfer. The publishing
 * service already has every object it needs (the account, the counterparty, the rate), so the
 * signed {@code amount} and human {@code description} are computed here, at the point of cause,
 * not reconstructed later from raw state. {@link ActivityEventListener} is the only thing that
 * ever turns this into a persisted row — see its javadoc for why that happens on a separate
 * listener rather than inline in the publishing service.
 */
public record ActivityRecordedEvent(
        String clientId, UUID accountId, ActivityType type, BigDecimal amount, String currency, String description) {
}
