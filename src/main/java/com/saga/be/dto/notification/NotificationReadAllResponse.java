package com.saga.be.dto.notification;

import java.time.LocalDateTime;

public record NotificationReadAllResponse(int updatedCount, LocalDateTime readAt) {}
