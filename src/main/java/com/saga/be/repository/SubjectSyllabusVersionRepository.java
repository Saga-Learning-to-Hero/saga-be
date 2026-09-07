package com.saga.be.repository;

import com.saga.be.entity.academic.SubjectSyllabusVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubjectSyllabusVersionRepository extends JpaRepository<SubjectSyllabusVersion, UUID> {

	@Query(
			"""
			SELECT s FROM SubjectSyllabusVersion s
			JOIN FETCH s.subject
			WHERE s.subject.id = :subjectId
			ORDER BY s.createdAt DESC
			""")
	List<SubjectSyllabusVersion> findBySubject_IdOrderByCreatedAtDesc(@Param("subjectId") UUID subjectId);

	@Query(
			"""
			SELECT s FROM SubjectSyllabusVersion s
			JOIN FETCH s.subject
			WHERE s.id = :id AND s.subject.id = :subjectId
			""")
	Optional<SubjectSyllabusVersion> findByIdAndSubject_Id(@Param("id") UUID id, @Param("subjectId") UUID subjectId);

	boolean existsBySubject_IdAndVersionLabelIgnoreCase(UUID subjectId, String versionLabel);

	boolean existsBySubject_IdAndVersionLabelIgnoreCaseAndIdNot(UUID subjectId, String versionLabel, UUID id);

	boolean existsBySubject_IdAndExternalSyllabusId(UUID subjectId, String externalSyllabusId);

	boolean existsBySubject_IdAndExternalSyllabusIdAndIdNot(UUID subjectId, String externalSyllabusId, UUID id);
}
