package com.saga.be.repository;

import com.saga.be.entity.academic.SyllabusExpectedDeliverable;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SyllabusExpectedDeliverableRepository extends JpaRepository<SyllabusExpectedDeliverable, UUID> {

	@Query(
			"""
			SELECT d FROM SyllabusExpectedDeliverable d
			JOIN FETCH d.phase
			WHERE d.syllabusVersionId = :syllabusVersionId
			ORDER BY d.orderIndex ASC
			""")
	List<SyllabusExpectedDeliverable> findBySyllabusVersion_IdOrderByOrderIndexAsc(
			@Param("syllabusVersionId") UUID syllabusVersionId);

	@Modifying
	@Transactional
	void deleteBySyllabusVersion_Id(UUID syllabusVersionId);
}
