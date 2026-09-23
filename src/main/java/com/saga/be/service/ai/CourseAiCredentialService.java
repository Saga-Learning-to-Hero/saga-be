package com.saga.be.service.ai;

import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.CourseAiProviderCredential;
import com.saga.be.entity.enums.AiCredentialStatus;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.CourseAiProviderCredentialRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the one-row-per-(course,role) credential lifecycle: save (encrypt, UNVERIFIED, never
 * calls the provider), safe-metadata read (never decrypts), revoke (clears secret material), and
 * the decrypt-for-execution path used only by {@link AiCredentialResolver} at analysis time. */
@Service @Profile("!test")
public class CourseAiCredentialService {
	private final CourseAiProviderCredentialRepository credentials;
	private final AiCredentialCipher cipher;

	public CourseAiCredentialService(CourseAiProviderCredentialRepository credentials, AiCredentialCipher cipher) {
		this.credentials = credentials; this.cipher = cipher;
	}

	public record SafeMetadata(boolean configured, String provider, AiProviderRole role, AiCredentialStatus status, String lastFour, LocalDateTime updatedAt, LocalDateTime lastSuccessfulUseAt) {
		static SafeMetadata absent(AiProviderRole role) { return new SafeMetadata(false, null, role, null, null, null, null); }
	}

	@Transactional(readOnly = true)
	public SafeMetadata safeMetadata(Course course, AiProviderRole role) {
		return credentials.findByCourse_IdAndProviderRole(course.getId(), role)
				.map(c -> new SafeMetadata(c.getStatus() != AiCredentialStatus.REVOKED, c.getProvider(), role, c.getStatus(), c.getLastFour(), c.getUpdatedAt(), c.getLastSuccessfulUseAt()))
				.orElseGet(() -> SafeMetadata.absent(role));
	}

	/** Save/replace: never calls the provider (status starts UNVERIFIED regardless of prior state)
	 * and never returns the key. Overwrites the same logical row for (course, role) in place. */
	@Transactional
	public SafeMetadata save(Course course, AiProviderRole role, String provider, String rawApiKey, UserAccount actor) {
		if (!cipher.isConfigured()) throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_MASTER_KEY_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE, "AI credential encryption is not configured on this server.");
		if (rawApiKey == null || rawApiKey.isBlank()) throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_INVALID_REQUEST, HttpStatus.BAD_REQUEST, "API key must not be blank.");
		AiCredentialCipher.Encrypted encrypted = cipher.encrypt(rawApiKey);
		CourseAiProviderCredential row = credentials.findByCourse_IdAndProviderRole(course.getId(), role).orElseGet(CourseAiProviderCredential::new);
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
		row = credentials.save(row);
		return new SafeMetadata(true, row.getProvider(), role, row.getStatus(), row.getLastFour(), row.getUpdatedAt(), row.getLastSuccessfulUseAt());
	}

	/** Revoke clears the secret material itself (not just a status flag) so a bug elsewhere can
	 * never accidentally decrypt a revoked credential -- there is nothing left to decrypt. */
	@Transactional
	public void revoke(Course course, AiProviderRole role) {
		Optional<CourseAiProviderCredential> existing = credentials.findByCourse_IdAndProviderRole(course.getId(), role);
		if (existing.isEmpty()) return;
		CourseAiProviderCredential row = existing.get();
		row.setStatus(AiCredentialStatus.REVOKED);
		row.setEncryptedSecret("");
		row.setEncryptionNonce("");
		row.setRevokedAt(LocalDateTime.now());
		credentials.save(row);
	}

	/** Decrypts for one execution's use only; callers must never persist, log, or cache the
	 * return value beyond the immediate transport-envelope build. */
	Optional<DecryptedCredential> decryptActive(java.util.UUID courseId, AiProviderRole role) {
		return credentials.findByCourse_IdAndProviderRole(courseId, role)
				.filter(c -> c.getStatus() != AiCredentialStatus.REVOKED)
				.map(c -> new DecryptedCredential(c.getId(), c.getFingerprint(), cipher.decrypt(c.getEncryptedSecret(), c.getEncryptionNonce(), c.getEncryptionKeyVersion())));
	}

	@Transactional
	public void markSuccessful(java.util.UUID credentialId) {
		credentials.findById(credentialId).ifPresent(c -> { c.setStatus(AiCredentialStatus.ACTIVE); c.setLastSuccessfulUseAt(LocalDateTime.now()); credentials.save(c); });
	}

	@Transactional
	public void markInvalid(java.util.UUID credentialId) {
		credentials.findById(credentialId).ifPresent(c -> { if (c.getStatus() != AiCredentialStatus.REVOKED) { c.setStatus(AiCredentialStatus.INVALID); credentials.save(c); } });
	}

	/** Quota/rate-limit/transient failures never invalidate a credential that hasn't been proven
	 * wrong -- only an auth failure (handled by {@link #markInvalid}) does that. */
	@Transactional
	public void markDegraded(java.util.UUID credentialId) {
		credentials.findById(credentialId).ifPresent(c -> { if (c.getStatus() == AiCredentialStatus.ACTIVE || c.getStatus() == AiCredentialStatus.UNVERIFIED) { c.setStatus(AiCredentialStatus.DEGRADED); credentials.save(c); } });
	}

	record DecryptedCredential(java.util.UUID credentialId, String fingerprint, String rawApiKey) {}

	private static String fingerprint(String rawApiKey) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawApiKey.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder(64);
			for (byte b : digest) out.append(String.format("%02x", b));
			return out.toString();
		} catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
	}
}
