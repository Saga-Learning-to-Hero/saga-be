package com.saga.be.repository;

import com.saga.be.entity.academic.SyllabusLearningOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface SyllabusLearningOutcomeRepository extends JpaRepository<SyllabusLearningOutcome, UUID> {

	List<SyllabusLearningOutcome> findBySyllabusVersion_IdOrderByOrderIndexAsc(UUID syllabusVersionId);

	@Modifying
	@Transactional
	void deleteBySyllabusVersion_Id(UUID syllabusVersionId);
}
