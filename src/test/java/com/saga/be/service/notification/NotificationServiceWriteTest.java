package com.saga.be.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.FcmProperties;
import com.saga.be.dto.notification.UserNotificationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.DeliveryStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.PushPlatform;
import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.entity.notification.NotificationBroadcast;
import com.saga.be.entity.notification.NotificationDelivery;
import com.saga.be.entity.notification.UserNotification;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.notification.NotificationCreatedEvent;
import com.saga.be.repository.FirebaseInstallationRepository;
import com.saga.be.repository.NotificationDeliveryRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.repository.UserNotificationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class NotificationServiceWriteTest {

	@Mock
	private UserNotificationRepository notifications;
	@Mock
	private UserAccountRepository users;
	@Mock
	private ApplicationEventPublisher events;
	@Mock
	private FirebaseInstallationRepository installations;
	@Mock
	private NotificationDeliveryRepository deliveries;

	@Test
	void duplicateEventKeyAfterLockReusesRowAndDoesNotPublish() {
		UUID recipientId = UUID.randomUUID();
		UserAccount recipient = recipient(recipientId);
		UserNotification existing = existingRow(recipient, "team-added:1");
		when(users.findByIdForUpdate(recipientId)).thenReturn(Optional.of(recipient));
		when(notifications.findByRecipientUser_IdAndEventKey(recipientId, "team-added:1"))
				.thenReturn(Optional.of(existing));

		UserNotificationResponse response = service()
				.createNotification(
						recipientId,
						NotificationType.TEAM,
						"Added to a team",
						"You were added to Team 1.",
						null,
						"team-added:1");

		assertEquals(existing.getId(), response.id());
		verify(notifications, never()).save(any());
		verify(events, never()).publishEvent(any());
		verify(deliveries, never()).save(any());
		verify(installations, never()).findActiveWithTokenByOwnerUserId(any());
	}

	@Test
	void createPublishesNotificationCreatedEvent() {
		UUID recipientId = UUID.randomUUID();
		UserAccount recipient = recipient(recipientId);
		when(users.findByIdForUpdate(recipientId)).thenReturn(Optional.of(recipient));
		when(notifications.save(any(UserNotification.class))).thenAnswer(invocation -> {
			UserNotification row = invocation.getArgument(0);
			row.setId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
			return row;
		});

		UserNotificationResponse response = service()
				.createNotification(
						recipientId, NotificationType.SYSTEM, "Hello", "Body", "/inbox", null);

		assertEquals(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), response.id());
		assertEquals("SYSTEM", response.notificationType());
		assertNull(response.readAt());
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(events).publishEvent(captor.capture());
		NotificationCreatedEvent event = (NotificationCreatedEvent) captor.getValue();
		assertEquals(recipientId, event.recipientUserId());
		assertEquals(response.id(), event.notificationId());
	}

	@Test
	void missingRecipientIsNotFound() {
		UUID recipientId = UUID.randomUUID();
		when(users.findByIdForUpdate(recipientId)).thenReturn(Optional.empty());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service()
						.createNotification(
								recipientId, NotificationType.SYSTEM, "Hello", "Body", null, "k1"));
		assertEquals(AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, ex.getCode());
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
		verify(notifications, never()).save(any());
		verify(events, never()).publishEvent(any());
	}

	@Test
	void blankTitleIsInvalid() {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service().createNotification(UUID.randomUUID(), NotificationType.SYSTEM, "  ", "Body", null, null));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, ex.getCode());
		assertSame(AcademicErrorCode.REQUEST_INVALID, ex.getCode());
	}

	@Test
	void createWithFcmEnabledPlansPendingDelivery() {
		UUID recipientId = UUID.randomUUID();
		UserAccount recipient = recipient(recipientId);
		FirebaseInstallation device = device(recipient, "token-1");
		when(users.findByIdForUpdate(recipientId)).thenReturn(Optional.of(recipient));
		when(notifications.save(any(UserNotification.class))).thenAnswer(invocation -> {
			UserNotification row = invocation.getArgument(0);
			row.setId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
			return row;
		});
		when(installations.findActiveWithTokenByOwnerUserId(recipientId)).thenReturn(List.of(device));
		when(deliveries.existsByNotification_IdAndInstallation_Id(any(), any())).thenReturn(false);

		service(true)
				.createNotification(recipientId, NotificationType.SYSTEM, "Hello", "Body", "/inbox", null);

		ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
		verify(deliveries).save(captor.capture());
		assertEquals(DeliveryStatus.PENDING, captor.getValue().getDeliveryStatus());
		assertEquals(device.getId(), captor.getValue().getInstallation().getId());
		assertEquals(0, captor.getValue().getAttemptCount());
		verify(events).publishEvent(any(NotificationCreatedEvent.class));
	}

	@Test
	void createAttachesBroadcastWhenProvided() {
		UUID recipientId = UUID.randomUUID();
		UserAccount recipient = recipient(recipientId);
		NotificationBroadcast broadcast = new NotificationBroadcast();
		broadcast.setId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
		when(users.findByIdForUpdate(recipientId)).thenReturn(Optional.of(recipient));
		when(notifications.save(any(UserNotification.class))).thenAnswer(invocation -> {
			UserNotification row = invocation.getArgument(0);
			row.setId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
			return row;
		});

		service().createNotification(
				recipientId, NotificationType.COURSE, "Hello", "Body", null, "manual:x:" + recipientId, broadcast);

		ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
		verify(notifications).save(captor.capture());
		assertSame(broadcast, captor.getValue().getBroadcast());
	}

	@Test
	void createWithFcmDisabledDoesNotPlanDeliveries() {
		UUID recipientId = UUID.randomUUID();
		UserAccount recipient = recipient(recipientId);
		when(users.findByIdForUpdate(recipientId)).thenReturn(Optional.of(recipient));
		when(notifications.save(any(UserNotification.class))).thenAnswer(invocation -> {
			UserNotification row = invocation.getArgument(0);
			row.setId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
			return row;
		});

		service(false)
				.createNotification(recipientId, NotificationType.SYSTEM, "Hello", "Body", "/inbox", null);

		verify(installations, never()).findActiveWithTokenByOwnerUserId(any());
		verify(deliveries, never()).save(any());
	}

	private NotificationService service() {
		return service(false);
	}

	private NotificationService service(boolean fcmEnabled) {
		FcmProperties properties = new FcmProperties();
		properties.setEnabled(fcmEnabled);
		return new NotificationService(notifications, users, events, installations, deliveries, properties);
	}

	private static UserAccount recipient(UUID id) {
		UserAccount account = new UserAccount();
		account.setId(id);
		account.setEmail(id + "@fpt.edu.vn");
		return account;
	}

	private static UserNotification existingRow(UserAccount recipient, String eventKey) {
		UserNotification row = new UserNotification();
		row.setId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
		row.setRecipientUser(recipient);
		row.setNotificationType(NotificationType.TEAM);
		row.setTitle("Added to a team");
		row.setMessage("You were added to Team 1.");
		row.setEventKey(eventKey);
		return row;
	}

	private static FirebaseInstallation device(UserAccount owner, String token) {
		FirebaseInstallation row = new FirebaseInstallation();
		row.setId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
		row.setOwnerUser(owner);
		row.setFirebaseInstallationId("fid-1");
		row.setFcmToken(token);
		row.setPlatform(PushPlatform.WEB);
		row.setActive(true);
		row.setLastRegisteredAt(LocalDateTime.of(2026, 1, 1, 0, 0));
		row.setVersion(0L);
		return row;
	}
}
