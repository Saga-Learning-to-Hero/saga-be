package com.saga.be.service.notification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.DeliveryStatus;
import com.saga.be.entity.enums.PushPlatform;
import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.entity.notification.UserNotification;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationDeliveryRulesTest {

	@Test
	void maySendOnlyWhenRecipientOwnsActiveInstallationWithToken() {
		UUID recipientId = UUID.randomUUID();
		UUID otherId = UUID.randomUUID();
		UserNotification notification = notification(recipientId);
		FirebaseInstallation ownedActive = installation(recipientId, true, "token");

		assertTrue(NotificationDeliveryRules.maySend(notification, ownedActive));
		assertTrue(NotificationDeliveryRules.maySend(recipientId, ownedActive));
		assertFalse(NotificationDeliveryRules.maySend(otherId, ownedActive));
		assertFalse(NotificationDeliveryRules.maySend(notification, installation(recipientId, false, "token")));
		assertFalse(NotificationDeliveryRules.maySend(notification, installation(recipientId, true, "  ")));
		assertFalse(NotificationDeliveryRules.maySend(notification, installation(otherId, true, "token")));
		assertFalse(NotificationDeliveryRules.maySend((UUID) null, ownedActive));
		assertFalse(NotificationDeliveryRules.maySend(notification, null));
	}

	@Test
	void reclaimedInstallationMustNotSendPreviousOwnersNotification() {
		UUID previousOwner = UUID.randomUUID();
		UUID newOwner = UUID.randomUUID();
		UserNotification stale = notification(previousOwner);
		FirebaseInstallation reclaimed = installation(newOwner, true, "new-token");

		assertFalse(NotificationDeliveryRules.maySend(stale, reclaimed));
		assertFalse(NotificationDeliveryRules.maySend(previousOwner, reclaimed));
		assertTrue(NotificationDeliveryRules.maySend(newOwner, reclaimed));
		assertEqualsSkippedContract();
	}

	private static void assertEqualsSkippedContract() {
		assertTrue(java.util.EnumSet.allOf(DeliveryStatus.class).contains(DeliveryStatus.SKIPPED));
	}

	private static UserNotification notification(UUID recipientId) {
		UserAccount recipient = new UserAccount();
		recipient.setId(recipientId);
		UserNotification row = new UserNotification();
		row.setRecipientUser(recipient);
		return row;
	}

	private static FirebaseInstallation installation(UUID ownerId, boolean active, String token) {
		UserAccount owner = new UserAccount();
		owner.setId(ownerId);
		FirebaseInstallation row = new FirebaseInstallation();
		row.setOwnerUser(owner);
		row.setActive(active);
		row.setFcmToken(token);
		row.setPlatform(PushPlatform.WEB);
		row.setFirebaseInstallationId("fid");
		row.setLastRegisteredAt(LocalDateTime.of(2026, 1, 1, 0, 0));
		if (!active) {
			row.setRevokedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
		}
		return row;
	}
}
