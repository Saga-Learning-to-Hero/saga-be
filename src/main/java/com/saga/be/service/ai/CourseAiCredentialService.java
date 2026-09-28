package com.saga.be.service.ai;

import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.CourseAiProviderCredential;
import com.saga.be.entity.enums.AiCredentialStatus;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.CourseAiProviderCredentialRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the one-row-per-(course, role, provider) credential lifecycle: save (encrypt, UNVERIFIED,
 * never calls the provider), safe-metadata read (never decrypts), revoke (clears secret material),
 * and the decrypt-for-execution path used only by {@link AiCredentialResolver} at analysis time. */
@Service @Profile("!test")
public class CourseAiCredentialService {
	private final CourseAiProviderCredentialRepository credentials;
	private final AiCredentialCipher cipher;

	public CourseAiCredentialService(CourseAiProviderCredentialRepository credentials, AiCredentialCipher cipher) {
		this.credentials = credentials; this.cipher = cipher;
	}

	public record SafeMetadata(boolean configured, AiProvider provider, AiProviderRole role, AiCredentialStatus status, String lastFour, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime lastSuccessfulUseAt) {
		static SafeMetadata absent(AiProviderRole role, AiProvider provider) { return new SafeMetadata(false, provider, role, null, null, null, null, null); }
		static SafeMetadata of(CourseAiProviderCredential c) { return new SafeMetadata(c.getStatus() != AiCredentialStatus.REVOKED, c.getProvider(), c.getProviderRole(), c.getStatus(), c.getLastFour(), c.getCreatedAt(), c.getUpdatedAt(), c.getLastSuccessfulUseAt()); }
	}

	@Transactional(readOnly = true)
	public SafeMetadata safeMetadata(Course course, AiProviderRole role, AiProvider provider) {
		return credentials.findByCourse_IdAndProviderRoleAndProvider(course.getId(), role, provider).map(SafeMetadata::of).orElseGet(() -> SafeMetadata.absent(role, provider));
	}

	/** Every stored credential row of the course (all roles/providers, revoked ones included so
	 * the lecturer can see what was revoked); never decrypts. */
	@Transactional(readOnly = true)
	public List<SafeMetadata> list(Course course) {
		return credentials.findByCourse_IdOrderByProviderRoleAscProviderAsc(course.getId()).stream().map(SafeMetadata::of).toList();
	}

	/** Save/replace: never calls the provider (status starts UNVERIFIED regardless of prior state)
	 * and never returns the key. Overwrites the same logical row for (course, role, provider). */
	@Transactional
	public SafeMetadata save(Course course, AiProviderRole role, AiProvider provider, String rawApiKey, UserAccount actor) {
		if (provider == null) throw new IntegrationException(IntegrationErrorCode.AI_PROVIDER_NOT_SUPPORTED, HttpStatus.BAD_REQUEST, "Unsupported AI provider.");
		if (!cipher.isConfigured()) throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_MASTER_KEY_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE, "AI credential encryption is not configured on this server.");
		if (rawApiKey == null || rawApiKey.isBlank()) throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_INVALID_REQUEST, HttpStatus.BAD_REQUEST, "API key must not be blank.");
		AiCredentialCipher.Encrypted encrypted = cipher.encrypt(rawApiKey);
		CourseAiProviderCredential row = credentials.findByCourse_IdAndProviderRoleAndProvider(course.getId(), role, provider).orElseGet(CourseAiProviderCredential::new);
		row.setCourse(course);
		row.setProviderRole(role);
		row.setProvider(provider);
		row.setEncryptedSecret(encrypted.ciphertextBase64());
		row.setEncryptionNonce(encrypted.nonceBase64());
		row.setEncryptionKeyVersion(encrypted.keyVersion());
		row.setFingerprint(fingerprint(rawApiKey));
		row.setLastFour(rawApiKey.length() >= 4 ? rawApiKey.substring(rawApiKey.length() - 4) : rawApiKey);
		row.setStatus(AiCredentialStatus.UNVERIFIED);
		row.setCreatedBy(actor);
		row.setRevokedAt(null);
		// Flushed so the response carries the real createdAt/updatedAt timestamps.
		row = credentials.saveAndFlush(row);
		return SafeMetadata.of(row);
	}

	/** Revoke clears the secret material itself (not just a status flag) so a bug elsewhere can
	 * never accidentally decrypt a revoked credential -- there is nothing left to decrypt. Only the
	 * (course, role, provider) row is touched; other providers' credentials are unaffected. */
	@Transactional
	public void revoke(Course course, AiProviderRole role, AiProvider provider) {
		Optional<CourseAiProviderCredential> existing = credentials.findByCourse_IdAndProviderRoleAndProvider(course.getId(), role, provider);
		if (existing.isEmpty()) return;
		CourseAiProviderCredential row = existing.get();
		row.setStatus(AiCredentialStatus.REVOKED);
		row.setEncryptedSecret("");
		row.setEncryptionNonce("");
		row.setRevokedAt(LocalDateTime.now());
		credentials.save(row);
	}

	/** Decrypts one specific credential for one execution's use only; callers must never persist,
	 * log, or cache the return value beyond the immediate transport-envelope build. The course/role
	 * checks make a credential id from another course or role unusable. */
	Optional<DecryptedCredential> decryptActive(UUID credentialId, UUID courseId, AiProviderRole role) {
		return credentials.findById(credentialId)
				.filter(c -> c.getCourse() != null && courseId.equals(c.getCourse().getId()) && c.getProviderRole() == role)
				.filter(c -> c.getStatus() != AiCredentialStatus.REVOKED)
				.map(c -> new DecryptedCredential(c.getId(), c.getFingerprint(), cipher.decrypt(c.getEncryptedSecret(), c.getEncryptionNonce(), c.getEncryptionKeyVersion())));
	}

	@Transactional
	public void markSuccessful(UUID credentialId) {
		credentials.findById(credentialId).ifPresent(c -> { if (c.getStatus() != AiCredentialStatus.REVOKED) { c.setStatus(AiCredentialStatus.ACTIVE); c.setLastSuccessfulUseAt(LocalDateTime.now()); credentials.save(c); } });
	}

	@Transactional
	public void markInvalid(UUID credentialId) {
		credentials.findById(credentialId).ifPresent(c -> { if (c.getStatus() != AiCredentialStatus.REVOKED) { c.setStatus(AiCredentialStatus.INVALID); credentials.save(c); } });
	}

	/** Quota/rate-limit failures never invalidate a credential that hasn't been proven wrong --
	 * only an auth failure (handled by {@link #markInvalid}) does that. */
	@Transactional
	public void markDegraded(UUID credentialId) {
		credentials.findById(credentialId).ifPresent(c -> { if (c.getStatus() == AiCredentialStatus.ACTIVE || c.getStatus() == AiCredentialStatus.UNVERIFIED) { c.setStatus(AiCredentialStatus.DEGRADED); credentials.save(c); } });
	}

	record DecryptedCredential(UUID credentialId, String fingerprint, String rawApiKey) {}

	private static String fingerprint(String rawApiKey) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawApiKey.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder(64);
			for (byte b : digest) out.append(String.format("%02x", b));
			return out.toString();
		} catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
	}
}
