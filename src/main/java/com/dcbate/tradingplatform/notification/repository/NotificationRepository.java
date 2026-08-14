package com.dcbate.tradingplatform.notification.repository;

import com.dcbate.tradingplatform.domain.Notification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** CRUD only — {@code NotificationServiceImpl} writes on success or terminal dead-letter, never in between. */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
}
