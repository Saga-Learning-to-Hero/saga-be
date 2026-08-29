package com.saga.be.repository;

import com.saga.be.entity.academic.SubjectSyllabusVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectSyllabusVersionRepository extends JpaRepository<SubjectSyllabusVersion, UUID> {

	List<SubjectSyllabusVersion> findBySubject_IdOrderByCreatedAtDesc(UUID subjectId);

	Optional<SubjectSyllabusVersion> findByIdAndSubject_Id(UUID id, UUID subjectId);

	boolean existsBySubject_IdAndVersionLabelIgnoreCase(UUID subjectId, String versionLabel);

	boolean existsBySubject_IdAndVersionLabelIgnoreCaseAndIdNot(UUID subjectId, String versionLabel, UUID id);

	boolean existsBySubject_IdAndExternalSyllabusId(UUID subjectId, String externalSyllabusId);

	boolean existsBySubject_IdAndExternalSyllabusIdAndIdNot(UUID subjectId, String externalSyllabusId, UUID id);
}
