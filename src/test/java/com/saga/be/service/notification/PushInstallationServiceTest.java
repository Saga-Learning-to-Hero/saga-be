package com.saga.be.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.notification.PushInstallationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.PushPlatform;
import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.FirebaseInstallationRepository;
import com.saga.be.repository.UserAccountRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PushInstallationServiceTest {

	@Mock
	private FirebaseInstallationRepository installations;
	@Mock
	private UserAccountRepository users;

	@Test
	void registerInsertsWhenFidIsNew() {
		UUID ownerId = UUID.randomUUID();
		UserAccount owner = owner(ownerId);
		when(users.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
		when(installations.findByFirebaseInstallationIdForUpdate("fid-1")).thenReturn(Optional.empty());
		when(installations.findByFcmTokenForUpdate("token-1")).thenReturn(Optional.empty());
		when(installations.save(any(FirebaseInstallation.class))).thenAnswer(invocation -> {
			FirebaseInstallation row = invocation.getArgument(0);
			row.setId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
			return row;
		});

		PushInstallationResponse response =
				service().register(ownerId, "fid-1", "token-1", PushPlatform.WEB);

		assertEquals(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), response.id());
		assertEquals("WEB", response.platform());
		assertTrue(response.active());
		ArgumentCaptor<FirebaseInstallation> captor = ArgumentCaptor.forClass(FirebaseInstallation.class);
		verify(installations).save(captor.capture());
		assertEquals("token-1", captor.getValue().getFcmToken());
		assertEquals("fid-1", captor.getValue().getFirebaseInstallationId());
		assertEquals(ownerId, captor.getValue().getOwnerUser().getId());
	}

	@Test
	void sameOwnerSameFidRotatesTokenAndReactivates() {
		UUID ownerId = UUID.randomUUID();
		UserAccount owner = owner(ownerId);
		FirebaseInstallation existing = existing(owner, "fid-1", "old-token");
		existing.setActive(false);
		existing.setRevokedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
		stubExistingFid(existing, "new-token");
		when(users.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
		when(installations.save(existing)).thenReturn(existing);

		PushInstallationResponse response =
				service().register(ownerId, "fid-1", "new-token", PushPlatform.WEB);

		assertEquals(existing.getId(), response.id());
		assertTrue(response.active());
		assertNull(existing.getRevokedAt());
		assertEquals("new-token", existing.getFcmToken());
		assertEquals(ownerId, existing.getOwnerUser().getId());
		verify(installations, never()).saveAndFlush(any());
	}

	@Test
	void activeForeignFidIsConflictWithoutReassign() {
		UUID adaId = UUID.randomUUID();
		UUID bobId = UUID.randomUUID();
		FirebaseInstallation adas = existing(owner(adaId), "fid-shared", "token-ada");
		when(users.findByIdForUpdate(bobId)).thenReturn(Optional.of(owner(bobId)));
		stubExistingFid(adas, "token-bob");

		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service().register(bobId, "fid-shared", "token-bob", PushPlatform.WEB));

		assertEquals(AcademicErrorCode.PUSH_INSTALLATION_CONFLICT, ex.getCode());
		assertEquals(HttpStatus.CONFLICT, ex.getStatus());
		assertFalse(ex.getMessage().contains(adaId.toString()));
		assertFalse(ex.getMessage().toLowerCase().contains("ada"));
		assertEquals(adaId, adas.getOwnerUser().getId());
		assertEquals("token-ada", adas.getFcmToken());
		assertTrue(adas.getActive());
		verify(installations, never()).save(any());
	}

	@Test
	void revokedForeignFidIsReclaimedByNewOwner() {
		UUID adaId = UUID.randomUUID();
		UUID bobId = UUID.randomUUID();
		UserAccount bob = owner(bobId);
		FirebaseInstallation revoked = existing(owner(adaId), "fid-shared", "old-token");
		revoked.setActive(false);
		revoked.setRevokedAt(LocalDateTime.of(2026, 3, 1, 0, 0));
		when(users.findByIdForUpdate(bobId)).thenReturn(Optional.of(bob));
		stubExistingFid(revoked, "token-bob");
		when(installations.save(revoked)).thenReturn(revoked);

		PushInstallationResponse response =
				service().register(bobId, "fid-shared", "token-bob", PushPlatform.WEB);

		assertEquals(revoked.getId(), response.id());
		assertTrue(response.active());
		assertNull(revoked.getRevokedAt());
		assertEquals(bobId, revoked.getOwnerUser().getId());
		assertEquals("token-bob", revoked.getFcmToken());
	}

	@Test
	void activeForeignTokenIsConflict() {
		UUID adaId = UUID.randomUUID();
		UUID bobId = UUID.randomUUID();
		FirebaseInstallation adasToken = existing(owner(adaId), "fid-ada", "shared-token");
		adasToken.setId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
		when(users.findByIdForUpdate(bobId)).thenReturn(Optional.of(owner(bobId)));
		when(installations.findByFcmToken("shared-token")).thenReturn(Optional.of(adasToken));
		when(installations.findByIdForUpdate(adasToken.getId())).thenReturn(Optional.of(adasToken));
		when(installations.findByFirebaseInstallationIdForUpdate("fid-bob")).thenReturn(Optional.empty());
		when(installations.findByFcmTokenForUpdate("shared-token")).thenReturn(Optional.of(adasToken));

		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service().register(bobId, "fid-bob", "shared-token", PushPlatform.WEB));

		assertEquals(AcademicErrorCode.PUSH_INSTALLATION_CONFLICT, ex.getCode());
		assertEquals(HttpStatus.CONFLICT, ex.getStatus());
		assertFalse(ex.getMessage().contains(adaId.toString()));
		assertEquals("shared-token", adasToken.getFcmToken());
		assertEquals(adaId, adasToken.getOwnerUser().getId());
		verify(installations, never()).saveAndFlush(any());
	}

	@Test
	void sameOwnerTokenMovesOffOtherFidWithoutRevoke() {
		UUID ownerId = UUID.randomUUID();
		UserAccount owner = owner(ownerId);
		FirebaseInstallation other = existing(owner, "fid-old", "token-shared");
		other.setId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
		when(users.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
		when(installations.findByFcmToken("token-shared")).thenReturn(Optional.of(other));
		when(installations.findByIdForUpdate(other.getId())).thenReturn(Optional.of(other));
		when(installations.findByFirebaseInstallationIdForUpdate("fid-new")).thenReturn(Optional.empty());
		when(installations.findByFcmTokenForUpdate("token-shared")).thenReturn(Optional.of(other));
		when(installations.saveAndFlush(other)).thenReturn(other);
		when(installations.save(any(FirebaseInstallation.class))).thenAnswer(invocation -> {
			FirebaseInstallation row = invocation.getArgument(0);
			if (row.getId() == null) {
				row.setId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
			}
			return row;
		});

		PushInstallationResponse response =
				service().register(ownerId, "fid-new", "token-shared", PushPlatform.WEB);

		assertEquals("dddddddd-dddd-dddd-dddd-dddddddddddd", response.id().toString());
		assertNull(other.getFcmToken());
		assertTrue(other.getActive());
		assertEquals(ownerId, other.getOwnerUser().getId());
	}

	@Test
	void revokeMissingIsNotFound() {
		UUID ownerId = UUID.randomUUID();
		UUID installationId = UUID.randomUUID();
		when(installations.findByIdAndOwnerUser_IdForUpdate(installationId, ownerId)).thenReturn(Optional.empty());
		AcademicException ex = assertThrows(
				AcademicException.class, () -> service().revoke(ownerId, installationId));
		assertEquals(AcademicErrorCode.PUSH_INSTALLATION_NOT_FOUND, ex.getCode());
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
	}

	@Test
	void revokeOwnIsIdempotent() {
		UUID ownerId = UUID.randomUUID();
		UserAccount owner = owner(ownerId);
		FirebaseInstallation existing = existing(owner, "fid-1", "token-1");
		existing.setActive(false);
		existing.setRevokedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
		when(installations.findByIdAndOwnerUser_IdForUpdate(existing.getId(), ownerId))
				.thenReturn(Optional.of(existing));

		PushInstallationResponse response = service().revoke(ownerId, existing.getId());
		assertFalse(response.active());
		assertEquals(LocalDateTime.of(2026, 1, 2, 0, 0), existing.getRevokedAt());
		verify(installations, never()).save(any());
	}

	@Test
	void blankFidIsInvalid() {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service().register(UUID.randomUUID(), "  ", "token", PushPlatform.WEB));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, ex.getCode());
	}

	private void stubExistingFid(FirebaseInstallation existing, String newToken) {
		when(installations.findByFirebaseInstallationId(existing.getFirebaseInstallationId()))
				.thenReturn(Optional.of(existing));
		when(installations.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
		when(installations.findByFirebaseInstallationIdForUpdate(existing.getFirebaseInstallationId()))
				.thenReturn(Optional.of(existing));
		when(installations.findByFcmTokenForUpdate(newToken)).thenReturn(Optional.empty());
	}

	private PushInstallationService service() {
		return new PushInstallationService(installations, users);
	}

	private static UserAccount owner(UUID id) {
		UserAccount account = new UserAccount();
		account.setId(id);
		account.setEmail(id + "@fpt.edu.vn");
		return account;
	}

	private static FirebaseInstallation existing(UserAccount owner, String fid, String token) {
		FirebaseInstallation row = new FirebaseInstallation();
		row.setId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
		row.setOwnerUser(owner);
		row.setFirebaseInstallationId(fid);
		row.setFcmToken(token);
		row.setPlatform(PushPlatform.WEB);
		row.setActive(true);
		row.setLastRegisteredAt(LocalDateTime.of(2026, 1, 1, 0, 0));
		row.setVersion(0L);
		return row;
	}
}
