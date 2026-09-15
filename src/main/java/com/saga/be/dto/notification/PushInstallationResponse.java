package com.saga.be.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Own push installation. Token and FID are never returned.")
public record PushInstallationResponse(UUID id, String platform, boolean active, LocalDateTime lastRegisteredAt) {}
