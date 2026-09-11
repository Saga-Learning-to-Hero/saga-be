package com.saga.be.auth;

import com.saga.be.dto.auth.UpdateProfileRequest;
import com.saga.be.dto.auth.UserProfileResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaAuthentications;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Self-service read/update for the authenticated user's own {@link UserAccount} profile fields.
 *
 * <p>Editable (USER_EDITABLE): {@code fullName}, {@code avatarUrl} — the only two fields that
 * exist in the current schema with no other authority owning them. Everything else returned by
 * {@link #getProfile} is read-only here: id/role/accountStatus are SYSTEM_MANAGED,
 * {@code googleSubject} is PROVIDER_MANAGED (never even exposed), {@code studentCode} is
 * institution-managed (ADMIN_MANAGED) and only ever read from {@link StudentProfile}, never
 * written by this service. {@code username} is treated as SYSTEM_MANAGED (assigned at
 * registration; no existing flow renames it, and it doubles as a login identifier) —
 * intentionally not made editable here; renaming it would be a separate, larger feature.
 * {@code email} is intentionally read-only: no verified-email-change flow exists yet.
 */
@Service
@Profile("!test")
public class UserProfileService {

	private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

	private final UserAccountRepository users;
	private final StudentProfileRepository students;

	public UserProfileService(UserAccountRepository users, StudentProfileRepository students) {
		this.users = users;
		this.students = students;
	}

	@Transactional(readOnly = true)
	public UserProfileResponse getProfile(UUID userId) {
		UserAccount account = requireAccount(userId);
		return toResponse(account);
	}

	/**
	 * Applies only the fields present in the request, then returns a fresh {@link Authentication}
	 * so the caller can re-establish the session — the session's cached {@code SagaUserPrincipal}
	 * snapshot (fullName/avatarUrl baked in at login) would otherwise stay stale for the rest of
	 * the session lifetime, and {@code GET /api/auth/me} would keep returning the old values.
	 */
	@Transactional
	public Authentication updateProfile(UUID userId, UpdateProfileRequest request) {
		UserAccount account = requireAccount(userId);
		if (request.fullName() != null) {
			String trimmed = request.fullName().trim();
			if (!StringUtils.hasText(trimmed)) {
				throw new AuthException(
						AuthErrorCode.PROFILE_FULL_NAME_INVALID, HttpStatus.BAD_REQUEST, "Full name cannot be blank.");
			}
			account.setFullName(trimmed);
		}
		if (request.avatarUrl() != null) {
			String trimmed = request.avatarUrl().trim();
			if (trimmed.isEmpty()) {
				account.setAvatarUrl(null); // explicit blank clears the avatar
			} else if (!isHttpUrl(trimmed)) {
				throw new AuthException(
						AuthErrorCode.PROFILE_AVATAR_URL_INVALID,
						HttpStatus.BAD_REQUEST,
						"Avatar URL must start with http:// or https://.");
			} else {
				account.setAvatarUrl(trimmed);
			}
		}
		UserAccount saved = users.save(account);
		log.info("auth method=PROFILE_UPDATE result=success userId={}", saved.getId());
		return SagaAuthentications.authenticated(saved);
	}

	private UserAccount requireAccount(UUID userId) {
		return users.findById(userId)
				.orElseThrow(() -> new AuthException(
						AuthErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Authentication failed."));
	}

	private UserProfileResponse toResponse(UserAccount account) {
		String studentCode = account.getAccountRole() == AccountRole.STUDENT
				? students.findByUserAccount_Id(account.getId()).map(StudentProfile::getStudentCode).orElse(null)
				: null;
		return new UserProfileResponse(
				account.getId(),
				account.getEmail(),
				account.getUsername(),
				account.getFullName(),
				account.getAvatarUrl(),
				account.getAccountRole() == null ? null : account.getAccountRole().name(),
				account.getAccountStatus() == null ? null : account.getAccountStatus().name(),
				studentCode);
	}

	private static boolean isHttpUrl(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://");
	}
}
