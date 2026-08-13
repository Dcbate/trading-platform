package com.dcbate.tradingplatform.notification.repository;

import com.dcbate.tradingplatform.domain.Notification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
}
