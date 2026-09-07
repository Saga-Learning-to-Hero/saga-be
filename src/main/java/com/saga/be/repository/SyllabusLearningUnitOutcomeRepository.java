package com.saga.be.repository;

import com.saga.be.entity.academic.SyllabusLearningUnitOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SyllabusLearningUnitOutcomeRepository extends JpaRepository<SyllabusLearningUnitOutcome, UUID> {

	@Query(
			"""
			SELECT l FROM SyllabusLearningUnitOutcome l
			JOIN FETCH l.learningUnit
			JOIN FETCH l.learningOutcome
			WHERE l.syllabusVersionId = :syllabusVersionId
			""")
	List<SyllabusLearningUnitOutcome> findBySyllabusVersion_Id(@Param("syllabusVersionId") UUID syllabusVersionId);

	@Modifying
	@Transactional
	void deleteBySyllabusVersion_Id(UUID syllabusVersionId);
}
