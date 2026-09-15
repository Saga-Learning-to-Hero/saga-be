package com.saga.be.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.PushPlatform;
import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.entity.notification.NotificationDelivery;
import com.saga.be.entity.notification.UserNotification;
import com.saga.be.push.FcmFailureCodes;
import com.saga.be.push.PushNotification;
import com.saga.be.push.PushNotificationSender;
import com.saga.be.push.PushSendException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FcmDeliveryWorkerTest {

	@Mock
	private FcmDeliveryService deliveries;
	@Mock
	private PushNotificationSender sender;

	@Test
	void disabledSenderDoesNotLoadDeliveries() {
		when(sender.isEnabled()).thenReturn(false);
		assertEquals(0, worker().processBatch());
		verify(deliveries, never()).findClaimableIds();
	}

	@Test
	void failedClaimDoesNotSend() {
		when(sender.isEnabled()).thenReturn(true);
		UUID id = UUID.randomUUID();
		when(deliveries.findClaimableIds()).thenReturn(List.of(id));
		when(deliveries.claim(id)).thenReturn(false);
		assertEquals(0, worker().processBatch());
		verify(sender, never()).send(any());
	}

	@Test
	void ownershipMismatchSkipsWithoutSend() {
		when(sender.isEnabled()).thenReturn(true);
		UUID id = UUID.randomUUID();
		when(deliveries.claim(id)).thenReturn(true);
		when(deliveries.loadForSend(id)).thenReturn(mismatch());
		assertFalse(worker().processOne(id));
		verify(deliveries).markSkipped(id, FcmFailureCodes.OWNERSHIP_MISMATCH);
		verify(sender, never()).send(any(PushNotification.class));
	}

	@Test
	void payloadInvalidArgumentFailsDeliveryWithoutRevokingInstallation() {
		when(sender.isEnabled()).thenReturn(true);
		UUID id = UUID.randomUUID();
		NotificationDelivery row = validOwned();
		UUID installationId = row.getInstallation().getId();
		when(deliveries.claim(id)).thenReturn(true);
		when(deliveries.loadForSend(id)).thenReturn(row);
		doThrow(new PushSendException(
						FcmFailureCodes.INVALID_ARGUMENT, false, false, "Invalid data payload key"))
				.when(sender)
				.send(any(PushNotification.class));
		assertFalse(worker().processOne(id));
		verify(deliveries).markTerminalFailure(id, FcmFailureCodes.INVALID_ARGUMENT);
		verify(deliveries, never()).revokeInvalidInstallation(installationId);
		verify(deliveries, never()).revokeInvalidInstallation(any());
	}

	private FcmDeliveryWorker worker() {
		return new FcmDeliveryWorker(deliveries, sender);
	}

	private static NotificationDelivery validOwned() {
		UserAccount owner = new UserAccount();
		owner.setId(UUID.randomUUID());
		UserNotification notification = new UserNotification();
		notification.setRecipientUser(owner);
		notification.setNotificationType(NotificationType.SYSTEM);
		notification.setTitle("Hello");
		notification.setMessage("Body");
		FirebaseInstallation installation = new FirebaseInstallation();
		installation.setId(UUID.randomUUID());
		installation.setOwnerUser(owner);
		installation.setActive(true);
		installation.setFcmToken("keep-this-token");
		installation.setPlatform(PushPlatform.WEB);
		NotificationDelivery row = new NotificationDelivery();
		row.setNotification(notification);
		row.setInstallation(installation);
		return row;
	}

	private static NotificationDelivery mismatch() {
		UserAccount recipient = new UserAccount();
		recipient.setId(UUID.randomUUID());
		UserAccount owner = new UserAccount();
		owner.setId(UUID.randomUUID());
		UserNotification notification = new UserNotification();
		notification.setRecipientUser(recipient);
		notification.setNotificationType(NotificationType.SYSTEM);
		notification.setTitle("Hello");
		notification.setMessage("Body");
		FirebaseInstallation installation = new FirebaseInstallation();
		installation.setOwnerUser(owner);
		installation.setActive(true);
		installation.setFcmToken("token");
		installation.setPlatform(PushPlatform.WEB);
		NotificationDelivery row = new NotificationDelivery();
		row.setNotification(notification);
		row.setInstallation(installation);
		return row;
	}
}
