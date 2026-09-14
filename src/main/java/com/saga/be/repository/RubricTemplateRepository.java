package com.saga.be.repository;

import com.saga.be.entity.assessment.RubricTemplate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RubricTemplateRepository extends JpaRepository<RubricTemplate, UUID> {

	List<RubricTemplate> findBySubjectIsNullAndDeletedAtIsNullOrderByCreatedAtAscIdAsc();

	List<RubricTemplate> findBySubject_IdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(UUID subjectId);
}
