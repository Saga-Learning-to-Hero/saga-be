package com.saga.be.dto.notification;

import java.util.List;

public record UserNotificationPageResponse(List<UserNotificationResponse> items, int page, int size, long total) {}
