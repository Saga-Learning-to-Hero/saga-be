package com.saga.be.service.notification;

import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.entity.notification.NotificationDelivery;
import com.saga.be.entity.notification.UserNotification;
import com.saga.be.push.FcmFailureCodes;
import com.saga.be.push.PushNotification;
import com.saga.be.push.PushNotificationSender;
import com.saga.be.push.PushSendException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Polls {@code notification_delivery}. Claim and finalize are short DB transactions. Firebase HTTP
 * runs outside any MySQL row lock.
 */
@Component
@Profile("!test")
public class FcmDeliveryWorker {

	private static final Logger log = LoggerFactory.getLogger(FcmDeliveryWorker.class);

	private final FcmDeliveryService deliveries;
	private final PushNotificationSender sender;

	public FcmDeliveryWorker(FcmDeliveryService deliveries, PushNotificationSender sender) {
		this.deliveries = deliveries;
		this.sender = sender;
	}

	@Scheduled(fixedDelayString = "${saga.fcm.worker.poll-delay:15s}")
	public void processDue() {
		processBatch();
	}

	public int processBatch() {
		if (!sender.isEnabled()) {
			return 0;
		}
		int sent = 0;
		for (UUID id : deliveries.findClaimableIds()) {
			if (processOne(id)) {
				sent++;
			}
		}
		return sent;
	}

	public boolean processOne(UUID id) {
		if (!sender.isEnabled()) {
			return false;
		}
		if (!deliveries.claim(id)) {
			return false;
		}
		NotificationDelivery row = deliveries.loadForSend(id);
		if (row == null) {
			deliveries.markSkipped(id, FcmFailureCodes.SKIPPED);
			return false;
		}
		UserNotification notification = row.getNotification();
		FirebaseInstallation installation = row.getInstallation();
		if (!NotificationDeliveryRules.maySend(notification, installation)) {
			deliveries.markSkipped(id, FcmDeliveryService.skipCode(notification, installation));
			return false;
		}
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("FCM send must not run inside a database transaction.");
		}
		try {
			sender.send(toPush(notification, installation));
			deliveries.markSent(id);
			return true;
		} catch (PushSendException ex) {
			return handleFailure(id, installation, ex);
		} catch (RuntimeException ex) {
			log.warn("fcm worker result=failure category={} deliveryPresent=true", FcmFailureCodes.UNKNOWN);
			deliveries.markRetryableFailure(id, FcmFailureCodes.UNKNOWN);
			return false;
		}
	}

	private boolean handleFailure(UUID deliveryId, FirebaseInstallation installation, PushSendException ex) {
		log.warn("fcm worker result=failure category={} deliveryPresent=true", ex.getFailureCode());
		if (ex.isTokenInvalid()) {
			if (installation != null) {
				deliveries.revokeInvalidInstallation(installation.getId());
			}
			deliveries.markTerminalFailure(deliveryId, ex.getFailureCode());
			return false;
		}
		if (ex.isRetryable()) {
			deliveries.markRetryableFailure(deliveryId, ex.getFailureCode());
			return false;
		}
		deliveries.markTerminalFailure(deliveryId, ex.getFailureCode());
		return false;
	}

	private static PushNotification toPush(UserNotification notification, FirebaseInstallation installation) {
		return new PushNotification(
				installation.getFcmToken(),
				notification.getTitle(),
				notification.getMessage(),
				notification.getId(),
				notification.getNotificationType() == null ? null : notification.getNotificationType().name(),
				notification.getActionUrl(),
				installation.getPlatform());
	}
}
