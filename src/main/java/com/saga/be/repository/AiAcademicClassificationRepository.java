package com.saga.be.repository;
import com.saga.be.entity.ai.AiAcademicClassification; import com.saga.be.entity.enums.*; import java.util.*; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface AiAcademicClassificationRepository extends JpaRepository<AiAcademicClassification, UUID> {
 @Query("select c from AiAcademicClassification c where c.project.id=:projectId and c.artifactType=:type and c.artifactId=:artifactId order by c.createdAt desc, c.id desc") List<AiAcademicClassification> findHistory(@Param("projectId") UUID projectId,@Param("type") AiArtifactType type,@Param("artifactId") UUID artifactId);
 @Modifying @Query("update AiAcademicClassification c set c.status=:next, c.reviewedAt=current_timestamp where c.id=:id and c.status=com.saga.be.entity.enums.AiAcademicClassificationStatus.PROPOSED and c.provenance=com.saga.be.entity.enums.AiAcademicProvenance.AI") int terminalReview(@Param("id") UUID id,@Param("next") AiAcademicClassificationStatus next);
 @Query("select c from AiAcademicClassification c where c.project.id=:projectId and c.artifactType=:type and c.artifactId=:artifactId and c.artifactRevision=:revision and ((c.provenance=com.saga.be.entity.enums.AiAcademicProvenance.AI and c.status=com.saga.be.entity.enums.AiAcademicClassificationStatus.CONFIRMED) or c.provenance=com.saga.be.entity.enums.AiAcademicProvenance.HUMAN)") List<AiAcademicClassification> findAuthoritative(@Param("projectId") UUID projectId,@Param("type") AiArtifactType type,@Param("artifactId") UUID artifactId,@Param("revision") String revision);

 /**
  * Confirmed-example retrieval (controlled learning, no fine-tuning): the exact same
  * "authoritative" definition as {@link #findAuthoritative} — an AI proposal the lecturer
  * CONFIRMED, or any lecturer CORRECT/HUMAN row — but scoped to the same pinned syllabus version
  * and target type (not one specific artifact), most-recent-first, and hard-capped via
  * {@code Pageable}. Never returns PROPOSED, REJECTED, or a corrected original AI row.
  */
 @Query("select c from AiAcademicClassification c where c.syllabusVersion.id=:syllabusVersionId and c.targetType=:targetType and ((c.provenance=com.saga.be.entity.enums.AiAcademicProvenance.AI and c.status=com.saga.be.entity.enums.AiAcademicClassificationStatus.CONFIRMED) or c.provenance=com.saga.be.entity.enums.AiAcademicProvenance.HUMAN) order by c.createdAt desc, c.id desc")
 List<AiAcademicClassification> findConfirmedExamples(@Param("syllabusVersionId") UUID syllabusVersionId, @Param("targetType") AiAcademicTargetType targetType, Pageable pageable);
}
