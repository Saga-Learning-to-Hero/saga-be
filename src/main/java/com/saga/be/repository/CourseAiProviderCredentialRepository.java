package com.saga.be.repository;

import com.saga.be.entity.ai.CourseAiProviderCredential;
import com.saga.be.entity.enums.AiProviderRole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseAiProviderCredentialRepository extends JpaRepository<CourseAiProviderCredential, UUID> {
	Optional<CourseAiProviderCredential> findByCourse_IdAndProviderRole(UUID courseId, AiProviderRole providerRole);
}
