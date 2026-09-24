package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.CourseAiProviderCredential;
import com.saga.be.entity.enums.AiCredentialStatus;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.exception.IntegrationException;
import com.saga.be.repository.CourseAiProviderCredentialRepository;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Section XVII: DB round-trip never stores plaintext; GET/save never returns the key. */
class CourseAiCredentialServiceTest {
	private static final String MASTER_KEY = Base64.getEncoder().encodeToString("M".repeat(32).getBytes());
	private CourseAiProviderCredentialRepository repository;
	private CourseAiCredentialService service;
	private Course course;
	private UserAccount actor;

	@BeforeEach
	void setUp() {
		repository = mock(CourseAiProviderCredentialRepository.class);
		service = new CourseAiCredentialService(repository, new AiCredentialCipher(MASTER_KEY));
		course = new Course();
		course.setId(UUID.randomUUID());
		actor = new UserAccount();
		actor.setId(UUID.randomUUID());
	}

	@Test
	void savingACredentialNeverPersistsThePlaintextKeyAnywhereInTheSavedRow() {
		when(repository.findByCourse_IdAndProviderRoleAndProvider(course.getId(), AiProviderRole.PRIMARY, AiProvider.OPENAI)).thenReturn(Optional.empty());
		when(repository.saveAndFlush(any(CourseAiProviderCredential.class))).thenAnswer(inv -> inv.getArgument(0));

		service.save(course, AiProviderRole.PRIMARY, AiProvider.OPENAI, "sk-super-secret-raw-key-123456", actor);

		var captor = org.mockito.ArgumentCaptor.forClass(CourseAiProviderCredential.class);
		verify(repository).saveAndFlush(captor.capture());
		CourseAiProviderCredential saved = captor.getValue();
		assertThat(saved.getEncryptedSecret()).doesNotContain("sk-super-secret-raw-key-123456");
		assertThat(saved.getLastFour()).isEqualTo("3456");
		assertThat(saved.getStatus()).isEqualTo(AiCredentialStatus.UNVERIFIED); // saving never calls the provider
		assertThat(saved.getFingerprint()).hasSize(64); // sha256 hex
	}

	@Test
	void savingTwiceForTheSameCourseAndRoleReplacesTheSameLogicalRowNotANewOne() {
		CourseAiProviderCredential existing = new CourseAiProviderCredential();
		existing.setCourse(course);
		existing.setProviderRole(AiProviderRole.PRIMARY);
		when(repository.findByCourse_IdAndProviderRoleAndProvider(course.getId(), AiProviderRole.PRIMARY, AiProvider.OPENAI)).thenReturn(Optional.of(existing));
		when(repository.saveAndFlush(any(CourseAiProviderCredential.class))).thenAnswer(inv -> inv.getArgument(0));

		service.save(course, AiProviderRole.PRIMARY, AiProvider.OPENAI, "sk-new-key-99999", actor);

		var captor = org.mockito.ArgumentCaptor.forClass(CourseAiProviderCredential.class);
		verify(repository).saveAndFlush(captor.capture());
		assertThat(captor.getValue()).isSameAs(existing); // same row, overwritten -- never a second row
	}

	@Test
	void safeMetadataNeverExposesTheKeyOrEncryptedSecret() {
		CourseAiProviderCredential row = new CourseAiProviderCredential();
		row.setProvider(AiProvider.OPENAI);
		row.setProviderRole(AiProviderRole.PRIMARY);
		row.setStatus(AiCredentialStatus.ACTIVE);
		row.setLastFour("6789");
		row.setEncryptedSecret("must-never-leak-this-either");
		when(repository.findByCourse_IdAndProviderRoleAndProvider(course.getId(), AiProviderRole.PRIMARY, AiProvider.OPENAI)).thenReturn(Optional.of(row));

		var meta = service.safeMetadata(course, AiProviderRole.PRIMARY, AiProvider.OPENAI);

		assertThat(meta.configured()).isTrue();
		assertThat(meta.lastFour()).isEqualTo("6789");
		assertThat(meta.toString()).doesNotContain("must-never-leak-this-either");
	}

	@Test
	void revokeClearsTheSecretMaterialNotJustTheStatusFlag() {
		CourseAiProviderCredential row = new CourseAiProviderCredential();
		row.setEncryptedSecret("some-ciphertext");
		row.setEncryptionNonce("some-nonce");
		row.setStatus(AiCredentialStatus.ACTIVE);
		when(repository.findByCourse_IdAndProviderRoleAndProvider(course.getId(), AiProviderRole.SECONDARY, AiProvider.OPENAI)).thenReturn(Optional.of(row));
		when(repository.save(any(CourseAiProviderCredential.class))).thenAnswer(inv -> inv.getArgument(0));

		service.revoke(course, AiProviderRole.SECONDARY, AiProvider.OPENAI);

		assertThat(row.getStatus()).isEqualTo(AiCredentialStatus.REVOKED);
		assertThat(row.getEncryptedSecret()).isEmpty();
		assertThat(row.getEncryptionNonce()).isEmpty();
		assertThat(row.getRevokedAt()).isNotNull();
	}

	@Test
	void savingWithoutAConfiguredMasterKeyFailsClosedRatherThanStoringAnything() {
		CourseAiCredentialService noKeyService = new CourseAiCredentialService(repository, new AiCredentialCipher(null));
		assertThatThrownBy(() -> noKeyService.save(course, AiProviderRole.PRIMARY, AiProvider.OPENAI, "sk-anything", actor))
				.isInstanceOf(IntegrationException.class);
		verify(repository, never()).save(any());
		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void blankApiKeyIsRejectedBeforeAnyEncryptionOrPersistence() {
		assertThatThrownBy(() -> service.save(course, AiProviderRole.PRIMARY, AiProvider.OPENAI, "   ", actor))
				.isInstanceOf(IntegrationException.class);
		verify(repository, never()).save(any());
		verify(repository, never()).saveAndFlush(any());
	}
}
