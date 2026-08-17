package com.dcbate.tradingplatform.activity.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.activity.repository.ActivityRepository;
import com.dcbate.tradingplatform.domain.Activity;
import com.dcbate.tradingplatform.domain.ActivityType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActivityEventListenerTest {

    @Mock
    private ActivityRepository activityRepository;

    @Test
    void turnsAnEventIntoAPersistedActivityRow() {
        ActivityEventListener listener = new ActivityEventListener(activityRepository);
        UUID accountId = UUID.randomUUID();
        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));

        listener.onActivityRecorded(new ActivityRecordedEvent(
                "client-1", accountId, ActivityType.DEPOSIT, new BigDecimal("50.00"), "GBP", "Deposit into Current GBP"));

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityRepository).save(captor.capture());
        Activity saved = captor.getValue();
        assertThat(saved.getActivityId()).isNotNull();
        assertThat(saved.getClientId()).isEqualTo("client-1");
        assertThat(saved.getAccountId()).isEqualTo(accountId);
        assertThat(saved.getType()).isEqualTo(ActivityType.DEPOSIT);
        assertThat(saved.getAmount()).isEqualByComparingTo("50.00");
        assertThat(saved.getCurrency()).isEqualTo("GBP");
        assertThat(saved.getDescription()).isEqualTo("Deposit into Current GBP");
        assertThat(saved.getOccurredAt()).isNotNull();
    }
}
