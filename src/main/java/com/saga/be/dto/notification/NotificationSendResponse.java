package com.saga.be.dto.notification;

import java.util.UUID;

public record NotificationSendResponse(UUID sendId, String scope, int recipientCount, int createdCount) {}
