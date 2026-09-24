package com.saga.be.repository;

import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiArtifactType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiAnalysisRunRepository extends JpaRepository<AiAnalysisRun, UUID> {
	Optional<AiAnalysisRun> findByIdempotencyKey(String idempotencyKey);
	Optional<AiAnalysisRun> findByCanonicalIdentityKeyAndRetryAttempt(String canonicalIdentityKey, Integer retryAttempt);
	Optional<AiAnalysisRun> findTopByCanonicalIdentityKeyOrderByRetryAttemptDesc(String canonicalIdentityKey);

	/** Left-joined: COURSE-scope progress narratives have project = null (course set instead). */
	@Query("select r from AiAnalysisRun r left join fetch r.project left join fetch r.course where r.id = :id")
	Optional<AiAnalysisRun> findFetchedById(@Param("id") UUID id);

	@Query("select r from AiAnalysisRun r join fetch r.project where r.id = :id and r.project.id = :projectId")
	Optional<AiAnalysisRun> findFetchedByIdAndProjectId(@Param("id") UUID id, @Param("projectId") UUID projectId);

	@Query("select r from AiAnalysisRun r join fetch r.course where r.id = :id and r.course.id = :courseId")
	Optional<AiAnalysisRun> findFetchedByIdAndCourseId(@Param("id") UUID id, @Param("courseId") UUID courseId);

	@Query(value = "select r from AiAnalysisRun r where r.project.id = :projectId and r.artifactId = :artifactId order by r.createdAt desc, r.id desc",
		countQuery = "select count(r) from AiAnalysisRun r where r.project.id = :projectId and r.artifactId = :artifactId")
	Page<AiAnalysisRun> findPageByProjectAndArtifact(@Param("projectId") UUID projectId, @Param("artifactId") UUID artifactId, Pageable pageable);

	@Modifying
	@Query("update AiAnalysisRun r set r.status = com.saga.be.entity.enums.AiAnalysisStatus.RUNNING, r.startedAt = :now, r.failureCode = null where r.id = :id and r.status = com.saga.be.entity.enums.AiAnalysisStatus.QUEUED")
	int claimQueued(@Param("id") UUID id, @Param("now") LocalDateTime now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisRun r set r.status = com.saga.be.entity.enums.AiAnalysisStatus.COMPLETED, r.completedAt = :now, r.failureCode = null where r.id = :id and r.status = com.saga.be.entity.enums.AiAnalysisStatus.RUNNING")
	int completeRunning(@Param("id") UUID id, @Param("now") LocalDateTime now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisRun r set r.status = com.saga.be.entity.enums.AiAnalysisStatus.FAILED, r.failureCode = :code, r.completedAt = :now where r.id = :id and r.status in (com.saga.be.entity.enums.AiAnalysisStatus.QUEUED, com.saga.be.entity.enums.AiAnalysisStatus.RUNNING)")
	int failActive(@Param("id") UUID id, @Param("code") String code, @Param("now") LocalDateTime now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisRun r set r.status = com.saga.be.entity.enums.AiAnalysisStatus.FAILED, r.failureCode = :code, r.completedAt = :now where r.id = :id and r.status = com.saga.be.entity.enums.AiAnalysisStatus.QUEUED")
	int failQueued(@Param("id") UUID id, @Param("code") String code, @Param("now") LocalDateTime now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisRun r set r.status = com.saga.be.entity.enums.AiAnalysisStatus.FAILED, r.failureCode = :code, r.completedAt = :now where r.id = :id and r.status = com.saga.be.entity.enums.AiAnalysisStatus.RUNNING and r.startedAt < :cutoff")
	int recoverStaleRunning(@Param("id") UUID id, @Param("cutoff") LocalDateTime cutoff, @Param("code") String code, @Param("now") LocalDateTime now);

	@Query("select r.id from AiAnalysisRun r where r.status = :status order by r.createdAt asc, r.id asc")
	List<UUID> findIdsByStatus(@Param("status") AiAnalysisStatus status, Pageable pageable);

	@Query("select r.id from AiAnalysisRun r where r.status = com.saga.be.entity.enums.AiAnalysisStatus.RUNNING and r.startedAt < :cutoff")
	List<UUID> findStaleRunningIds(@Param("cutoff") LocalDateTime cutoff);

	/** Lecturer drill-down: latest run for one artifact within a project, any status. */
	Optional<AiAnalysisRun> findTopByProject_IdAndArtifactTypeAndArtifactIdAndAnalysisTypeOrderByCreatedAtDesc(UUID projectId, AiArtifactType artifactType, UUID artifactId, AiAnalysisType analysisType);

	/** Lecturer drill-down: latest COURSE-scope run. */
	Optional<AiAnalysisRun> findTopByCourse_IdAndArtifactTypeAndArtifactIdAndAnalysisTypeOrderByCreatedAtDesc(UUID courseId, AiArtifactType artifactType, UUID artifactId, AiAnalysisType analysisType);
}
