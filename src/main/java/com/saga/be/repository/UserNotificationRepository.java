package com.saga.be.repository;

import com.saga.be.entity.notification.UserNotification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNotificationRepository extends JpaRepository<UserNotification, UUID> {}
