package com.saga.be.dto.notification;

import com.saga.be.entity.enums.PushPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterPushInstallationRequest(
		@NotBlank @Size(max = 255) String firebaseInstallationId,
		@NotBlank @Size(max = 512) String fcmToken,
		@NotNull PushPlatform platform) {}
