package com.saga.be.repository;

import com.saga.be.entity.academic.SyllabusExpectedActivity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SyllabusExpectedActivityRepository extends JpaRepository<SyllabusExpectedActivity, UUID> {

	@Query(
			"""
			SELECT a FROM SyllabusExpectedActivity a
			JOIN FETCH a.phase
			WHERE a.syllabusVersionId = :syllabusVersionId
			ORDER BY a.orderIndex ASC
			""")
	List<SyllabusExpectedActivity> findBySyllabusVersion_IdOrderByOrderIndexAsc(
			@Param("syllabusVersionId") UUID syllabusVersionId);

	@Modifying
	@Transactional
	void deleteBySyllabusVersion_Id(UUID syllabusVersionId);
}
