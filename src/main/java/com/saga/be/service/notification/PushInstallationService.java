package com.saga.be.service.notification;

import com.saga.be.dto.notification.PushInstallationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.PushPlatform;
import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.FirebaseInstallationRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.web.RequestTiming;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Own-device FCM registration. Canonical upsert key is Firebase Installation ID (FID).
 * {@code fcm_token} is a distinct send address and is never returned to clients.
 *
 * <p>Do not catch unique-constraint failures as control flow. Writers lock existing
 * installation rows by sorted primary key, then {@code SELECT ... FOR UPDATE} on FID
 * (gap-lock for inserts) and token. Unique constraints remain the last line of defense.
 * A MySQL deadlock aborts this transaction; the client retries PUT in a new request.
 *
 * <p>An ACTIVE installation is never silently reassigned across users. A REVOKED FID
 * may be reclaimed by a new owner because send is gated by
 * {@link NotificationDeliveryRules}: recipient must still equal owner and active must
 * be true.
 */
@Service
@Profile("!test")
public class PushInstallationService {

	private final FirebaseInstallationRepository installations;
	private final UserAccountRepository users;

	public PushInstallationService(FirebaseInstallationRepository installations, UserAccountRepository users) {
		this.installations = installations;
		this.users = users;
	}

	@Transactional
	public PushInstallationResponse register(
			UUID ownerUserId, String firebaseInstallationId, String fcmToken, PushPlatform platform) {
		return RequestTiming.record("registerPushInstallation", () -> {
			if (ownerUserId == null) {
				throw invalid("recipient is required.");
			}
			if (platform == null) {
				throw invalid("platform is required.");
			}
			String fid = requireText(firebaseInstallationId, 255, "firebaseInstallationId");
			String token = requireText(fcmToken, 512, "fcmToken");
			UserAccount owner = users.findByIdForUpdate(ownerUserId).orElseThrow(() -> new AcademicException(
					AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "User was not found."));
			lockExistingRowsInIdOrder(fid, token);
			FirebaseInstallation byFid = installations.findByFirebaseInstallationIdForUpdate(fid).orElse(null);
			FirebaseInstallation byToken = installations.findByFcmTokenForUpdate(token).orElse(null);
			if (byToken != null) {
				installations.findByIdForUpdate(byToken.getId());
			}
			if (byFid != null && !ownerUserId.equals(byFid.getOwnerUser().getId()) && isActive(byFid)) {
				throw conflict();
			}
			detachTokenIfSafe(byToken, byFid, ownerUserId);
			LocalDateTime now = LocalDateTime.now();
			if (byFid != null) {
				byFid.setOwnerUser(owner);
				byFid.setFcmToken(token);
				byFid.setPlatform(platform);
				byFid.setActive(true);
				byFid.setRevokedAt(null);
				byFid.setLastRegisteredAt(now);
				return toResponse(installations.save(byFid));
			}
			FirebaseInstallation created = new FirebaseInstallation();
			created.setOwnerUser(owner);
			created.setFirebaseInstallationId(fid);
			created.setFcmToken(token);
			created.setPlatform(platform);
			created.setActive(true);
			created.setRevokedAt(null);
			created.setLastRegisteredAt(now);
			return toResponse(installations.save(created));
		});
	}

	@Transactional
	public PushInstallationResponse revoke(UUID ownerUserId, UUID installationId) {
		return RequestTiming.record("revokePushInstallation", () -> {
			if (ownerUserId == null || installationId == null) {
				throw notFound();
			}
			FirebaseInstallation row = installations
					.findByIdAndOwnerUser_IdForUpdate(installationId, ownerUserId)
					.orElseThrow(PushInstallationService::notFound);
			if (Boolean.TRUE.equals(row.getActive()) || row.getRevokedAt() == null) {
				row.setActive(false);
				if (row.getRevokedAt() == null) {
					row.setRevokedAt(LocalDateTime.now());
				}
				installations.save(row);
			}
			return toResponse(row);
		});
	}

	private void lockExistingRowsInIdOrder(String fid, String token) {
		Set<UUID> ids = new LinkedHashSet<>();
		installations.findByFirebaseInstallationId(fid).map(FirebaseInstallation::getId).ifPresent(ids::add);
		installations.findByFcmToken(token).map(FirebaseInstallation::getId).ifPresent(ids::add);
		List<UUID> ordered = new ArrayList<>(ids);
		ordered.sort(Comparator.naturalOrder());
		for (UUID id : ordered) {
			installations.findByIdForUpdate(id);
		}
	}

	private void detachTokenIfSafe(FirebaseInstallation byToken, FirebaseInstallation byFid, UUID ownerUserId) {
		if (byToken == null || (byFid != null && byToken.getId().equals(byFid.getId()))) {
			return;
		}
		boolean sameOwner = ownerUserId.equals(byToken.getOwnerUser().getId());
		if (isActive(byToken) && !sameOwner) {
			throw conflict();
		}
		byToken.setFcmToken(null);
		installations.saveAndFlush(byToken);
	}

	private static boolean isActive(FirebaseInstallation row) {
		return Boolean.TRUE.equals(row.getActive());
	}

	private static PushInstallationResponse toResponse(FirebaseInstallation row) {
		return new PushInstallationResponse(
				row.getId(),
				row.getPlatform() == null ? null : row.getPlatform().name(),
				Boolean.TRUE.equals(row.getActive()),
				row.getLastRegisteredAt());
	}

	private static String requireText(String value, int maxLength, String field) {
		if (!StringUtils.hasText(value)) {
			throw invalid(field + " is required.");
		}
		String trimmed = value.trim();
		if (trimmed.length() > maxLength) {
			throw invalid(field + " is too long.");
		}
		return trimmed;
	}

	private static AcademicException notFound() {
		return new AcademicException(
				AcademicErrorCode.PUSH_INSTALLATION_NOT_FOUND,
				HttpStatus.NOT_FOUND,
				"Push installation was not found.");
	}

	private static AcademicException conflict() {
		return new AcademicException(
				AcademicErrorCode.PUSH_INSTALLATION_CONFLICT,
				HttpStatus.CONFLICT,
				"Push installation is already registered.");
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST, message);
	}
}
