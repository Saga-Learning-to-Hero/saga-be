package com.saga.be.repository;

import com.saga.be.entity.ai.CourseAiProviderCredential;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseAiProviderCredentialRepository extends JpaRepository<CourseAiProviderCredential, UUID> {
	Optional<CourseAiProviderCredential> findByCourse_IdAndProviderRoleAndProvider(UUID courseId, AiProviderRole providerRole, AiProvider provider);

	List<CourseAiProviderCredential> findByCourse_IdOrderByProviderRoleAscProviderAsc(UUID courseId);
}
