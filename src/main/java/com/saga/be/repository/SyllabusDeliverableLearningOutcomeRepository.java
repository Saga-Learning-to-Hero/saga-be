package com.saga.be.repository;

import com.saga.be.entity.academic.SyllabusDeliverableLearningOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface SyllabusDeliverableLearningOutcomeRepository
		extends JpaRepository<SyllabusDeliverableLearningOutcome, UUID> {

	List<SyllabusDeliverableLearningOutcome> findBySyllabusVersion_Id(UUID syllabusVersionId);

	@Modifying
	@Transactional
	void deleteBySyllabusVersion_Id(UUID syllabusVersionId);
}
