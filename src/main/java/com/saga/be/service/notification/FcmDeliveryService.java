package com.saga.be.service.notification;

import com.saga.be.config.FcmProperties;
import com.saga.be.entity.enums.DeliveryStatus;
import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.entity.notification.NotificationDelivery;
import com.saga.be.entity.notification.UserNotification;
import com.saga.be.push.FcmFailureCodes;
import com.saga.be.repository.FirebaseInstallationRepository;
import com.saga.be.repository.NotificationDeliveryRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class FcmDeliveryService {

	private final NotificationDeliveryRepository deliveries;
	private final FirebaseInstallationRepository installations;
	private final FcmProperties properties;
	private final Clock clock;

	@Autowired
	public FcmDeliveryService(
			NotificationDeliveryRepository deliveries,
			FirebaseInstallationRepository installations,
			FcmProperties properties) {
		this(deliveries, installations, properties, Clock.systemDefaultZone());
	}

	public FcmDeliveryService(
			NotificationDeliveryRepository deliveries,
			FirebaseInstallationRepository installations,
			FcmProperties properties,
			Clock clock) {
		this.deliveries = deliveries;
		this.installations = installations;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<UUID> findClaimableIds() {
		FcmProperties.Worker worker = properties.getWorker();
		LocalDateTime current = now();
		return deliveries.findClaimableIds(
				worker.getMaxAttempts(),
				staleBefore(current),
				retryBefore(current),
				PageRequest.of(0, Math.max(1, worker.getBatchSize())));
	}

	@Transactional
	public boolean claim(UUID id) {
		LocalDateTime current = now();
		return deliveries.claim(
						id,
						current,
						properties.getWorker().getMaxAttempts(),
						staleBefore(current),
						retryBefore(current))
				== 1;
	}

	@Transactional(readOnly = true)
	public NotificationDelivery loadForSend(UUID id) {
		return deliveries.findByIdWithNotificationAndInstallation(id).orElse(null);
	}

	@Transactional
	public void markSent(UUID id) {
		deliveries.findByIdForUpdate(id).ifPresent(row -> {
			row.setDeliveryStatus(DeliveryStatus.SENT);
			row.setSentAt(now());
			row.setProcessingStartedAt(null);
			row.setFailureCode(null);
			deliveries.save(row);
		});
	}

	@Transactional
	public void markSkipped(UUID id, String failureCode) {
		deliveries.findByIdForUpdate(id).ifPresent(row -> {
			row.setDeliveryStatus(DeliveryStatus.SKIPPED);
			row.setProcessingStartedAt(null);
			row.setFailureCode(FcmFailureCodes.truncate(failureCode));
			row.setLastAttemptAt(now());
			deliveries.save(row);
		});
	}

	@Transactional
	public void markRetryableFailure(UUID id, String failureCode) {
		deliveries.findByIdForUpdate(id).ifPresent(row -> {
			int attempts = row.getAttemptCount() == null ? 0 : row.getAttemptCount();
			row.setFailureCode(FcmFailureCodes.truncate(failureCode));
			row.setLastAttemptAt(now());
			row.setProcessingStartedAt(null);
			if (attempts >= properties.getWorker().getMaxAttempts()) {
				row.setDeliveryStatus(DeliveryStatus.FAILED);
			} else {
				row.setDeliveryStatus(DeliveryStatus.PENDING);
			}
			deliveries.save(row);
		});
	}

	@Transactional
	public void markTerminalFailure(UUID id, String failureCode) {
		deliveries.findByIdForUpdate(id).ifPresent(row -> {
			row.setDeliveryStatus(DeliveryStatus.FAILED);
			row.setFailureCode(FcmFailureCodes.truncate(failureCode));
			row.setLastAttemptAt(now());
			row.setProcessingStartedAt(null);
			deliveries.save(row);
		});
	}

	@Transactional
	public void revokeInvalidInstallation(UUID installationId) {
		if (installationId == null) {
			return;
		}
		installations.findByIdForUpdate(installationId).ifPresent(row -> {
			row.setActive(false);
			row.setFcmToken(null);
			if (row.getRevokedAt() == null) {
				row.setRevokedAt(now());
			}
			installations.save(row);
		});
	}

	static String skipCode(UserNotification notification, FirebaseInstallation installation) {
		if (installation == null || !Boolean.TRUE.equals(installation.getActive())) {
			return FcmFailureCodes.INSTALLATION_INACTIVE;
		}
		if (installation.getFcmToken() == null || installation.getFcmToken().isBlank()) {
			return FcmFailureCodes.TOKEN_MISSING;
		}
		if (notification == null
				|| notification.getRecipientUser() == null
				|| installation.getOwnerUser() == null
				|| !notification.getRecipientUser().getId().equals(installation.getOwnerUser().getId())) {
			return FcmFailureCodes.OWNERSHIP_MISMATCH;
		}
		return FcmFailureCodes.SKIPPED;
	}

	private LocalDateTime staleBefore(LocalDateTime current) {
		return current.minus(properties.getWorker().getClaimStaleAfter());
	}

	private LocalDateTime retryBefore(LocalDateTime current) {
		return current.minus(properties.getWorker().getRetryDelay());
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}
}
