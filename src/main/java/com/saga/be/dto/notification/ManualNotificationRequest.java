package com.saga.be.dto.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ManualNotificationRequest(
		@NotBlank @Size(max = 160) String title,
		@NotBlank @Size(max = 1000) String message,
		@Size(max = 500) String actionUrl) {}
