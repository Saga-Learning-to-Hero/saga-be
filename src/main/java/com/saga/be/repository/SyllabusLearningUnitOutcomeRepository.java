package com.saga.be.repository;

import com.saga.be.entity.academic.SyllabusLearningUnitOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface SyllabusLearningUnitOutcomeRepository extends JpaRepository<SyllabusLearningUnitOutcome, UUID> {

	List<SyllabusLearningUnitOutcome> findBySyllabusVersion_Id(UUID syllabusVersionId);

	@Modifying
	@Transactional
	void deleteBySyllabusVersion_Id(UUID syllabusVersionId);
}
