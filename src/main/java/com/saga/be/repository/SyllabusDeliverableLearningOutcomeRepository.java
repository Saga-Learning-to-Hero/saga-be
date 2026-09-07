package com.saga.be.repository;

import com.saga.be.entity.academic.SyllabusDeliverableLearningOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SyllabusDeliverableLearningOutcomeRepository
		extends JpaRepository<SyllabusDeliverableLearningOutcome, UUID> {

	@Query(
			"""
			SELECT l FROM SyllabusDeliverableLearningOutcome l
			JOIN FETCH l.deliverable
			JOIN FETCH l.learningOutcome
			WHERE l.syllabusVersionId = :syllabusVersionId
			""")
	List<SyllabusDeliverableLearningOutcome> findBySyllabusVersion_Id(@Param("syllabusVersionId") UUID syllabusVersionId);

	@Modifying
	@Transactional
	void deleteBySyllabusVersion_Id(UUID syllabusVersionId);
}
