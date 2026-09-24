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

 /**
  * Course-wide history read: every classification row (all statuses and provenances, same
  * semantic as {@link #findHistory}) for every project whose course is {@code courseId}, in the
  * same {@code createdAt desc, id desc} order. Course scope and every filter are applied in SQL.
  * All joins are to-one (a project has at most one team via uk_team_project), so rows never
  * duplicate. Task/commit display columns are only populated when that artifact belongs to the
  * classification's own project.
  */
 @Query(
		 value =
				 """
				 select new com.saga.be.repository.LecturerCourseAcademicClassificationRow(
						 c.id, c.artifactType, c.artifactId, c.artifactRevision,
						 sv.id, c.targetType,
						 ph.id, ph.code, ph.name,
						 dl.id, dl.code, dl.name,
						 c.confidence, c.aiSummary, c.status, c.provenance,
						 src.id, c.reviewedAt, c.createdAt,
						 p.id, p.name, t.id, t.name,
						 tk.externalKey, tk.title,
						 case when gr.id is null then null else gc.shaHash end,
						 case when gr.id is null then null else gc.message end)
				 from AiAcademicClassification c
				 join c.project p
				 left join Team t on t.project = p
				 left join c.syllabusVersion sv
				 left join c.phase ph
				 left join c.deliverable dl
				 left join c.sourceClassification src
				 left join Task tk on c.artifactType = com.saga.be.entity.enums.AiArtifactType.TASK
						 and tk.id = c.artifactId and tk.project = p
				 left join GitCommit gc on c.artifactType = com.saga.be.entity.enums.AiArtifactType.COMMIT
						 and gc.id = c.artifactId
				 left join gc.repo gr on gr.project = p
				 where p.course.id = :courseId
				   and (:artifactType is null or c.artifactType = :artifactType)
				   and (:status is null or c.status = :status)
				   and (:projectId is null or p.id = :projectId)
				   and (:teamId is null or t.id = :teamId)
				 order by c.createdAt desc, c.id desc
				 """,
		 countQuery =
				 """
				 select count(c.id)
				 from AiAcademicClassification c
				 join c.project p
				 left join Team t on t.project = p
				 where p.course.id = :courseId
				   and (:artifactType is null or c.artifactType = :artifactType)
				   and (:status is null or c.status = :status)
				   and (:projectId is null or p.id = :projectId)
				   and (:teamId is null or t.id = :teamId)
				 """)
 org.springframework.data.domain.Page<com.saga.be.repository.LecturerCourseAcademicClassificationRow> findCoursePage(
		 @Param("courseId") UUID courseId,
		 @Param("artifactType") AiArtifactType artifactType,
		 @Param("status") AiAcademicClassificationStatus status,
		 @Param("projectId") UUID projectId,
		 @Param("teamId") UUID teamId,
		 Pageable pageable);
}
