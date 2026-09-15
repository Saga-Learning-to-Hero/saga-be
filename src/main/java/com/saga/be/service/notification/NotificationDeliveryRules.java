package com.saga.be.service.notification;

import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.entity.notification.UserNotification;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * Future FCM worker send gate. Inbox rows stay the source of truth. A delivery must never
 * be sent unless the installation still belongs to the notification recipient and is active.
 *
 * <p>If this returns {@code false}, set {@code notification_delivery.delivery_status = SKIPPED}
 * and do not call Firebase. That covers a later owner of the same physical browser/FID.
 */
public final class NotificationDeliveryRules {

	private NotificationDeliveryRules() {}

	public static boolean maySend(UserNotification notification, FirebaseInstallation installation) {
		if (notification == null || notification.getRecipientUser() == null) {
			return false;
		}
		return maySend(notification.getRecipientUser().getId(), installation);
	}

	public static boolean maySend(UUID notificationRecipientUserId, FirebaseInstallation installation) {
		if (notificationRecipientUserId == null || installation == null) {
			return false;
		}
		if (!Boolean.TRUE.equals(installation.getActive())) {
			return false;
		}
		if (installation.getOwnerUser() == null || installation.getOwnerUser().getId() == null) {
			return false;
		}
		if (!notificationRecipientUserId.equals(installation.getOwnerUser().getId())) {
			return false;
		}
		return StringUtils.hasText(installation.getFcmToken());
	}
}
