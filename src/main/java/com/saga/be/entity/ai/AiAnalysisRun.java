package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiArtifactType;
import com.saga.be.entity.project.Project;
import jakarta.persistence.*;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

@Getter @Setter @NoArgsConstructor @Entity
@Table(name = "ai_analysis_run", uniqueConstraints = {
	@UniqueConstraint(name = "uk_ai_analysis_run_idempotency", columnNames = "idempotency_key"),
	@UniqueConstraint(name = "uk_ai_analysis_run_canonical_retry", columnNames = {"canonical_identity_key", "retry_attempt"})}, indexes = {
	@Index(name = "ix_ai_analysis_run_project_artifact_created", columnList = "project_id, artifact_type, artifact_id, created_at"),
	@Index(name = "ix_ai_analysis_run_status_started", columnList = "status, started_at")})
public class AiAnalysisRun extends BaseEntity {
	/** Null only for COURSE-scope progress narratives, which have no single owning project. */
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "project_id") private Project project;
	/** Set only for COURSE-scope progress narratives; null for every other analysis type/scope. */
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "course_id") private Course course;
	@Enumerated(EnumType.STRING) @Column(name = "artifact_type", length = 32, nullable = false) private AiArtifactType artifactType;
	@JdbcTypeCode(Types.CHAR) @Column(name = "artifact_id", columnDefinition = "char(36)", nullable = false) private UUID artifactId;
	@Column(name = "artifact_revision", length = 128, nullable = false) private String artifactRevision;
	@Enumerated(EnumType.STRING) @Column(name = "analysis_type", length = 64, nullable = false) private AiAnalysisType analysisType;
	@Enumerated(EnumType.STRING) @Column(name = "status", length = 32, nullable = false) private AiAnalysisStatus status;
	@Column(name = "evidence_hash", length = 64, nullable = false) private String evidenceHash;
	@Column(name = "policy_version", length = 64, nullable = false) private String policyVersion;
	@Column(name = "prompt_version", length = 64, nullable = false) private String promptVersion;
	@Column(name = "schema_version", length = 64, nullable = false) private String schemaVersion;
	@Column(name = "taxonomy_version", length = 64) private String taxonomyVersion;
	@Column(name = "provider_config_hash", length = 64, nullable = false) private String providerConfigHash;
	@Column(name = "idempotency_key", length = 64, nullable = false) private String idempotencyKey;
	@Column(name = "canonical_identity_key", length = 64, nullable = false) private String canonicalIdentityKey;
	@Column(name = "retry_attempt", nullable = false) private Integer retryAttempt;
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "requested_by_user_id") private UserAccount requestedBy;
	@Column(name = "started_at") private LocalDateTime startedAt;
	@Column(name = "completed_at") private LocalDateTime completedAt;
	@Column(name = "failure_code", length = 64) private String failureCode;

	/** Direct repository callers still create a truthful first physical execution. Submission
	 * services explicitly set lineage before persistence for both initial and retry attempts. */
	@PrePersist
	void initializeRetryLineage() {
		if (canonicalIdentityKey == null) canonicalIdentityKey = idempotencyKey;
		if (retryAttempt == null) retryAttempt = 0;
	}

	/** Every analysis type is owned by exactly one course, either directly (COURSE-scope progress
	 * narrative) or transitively through its project. Null only if the project itself has no
	 * course, which should not happen for any real project. */
	public UUID resolveCourseId() {
		if (course != null) return course.getId();
		if (project != null && project.getCourse() != null) return project.getCourse().getId();
		return null;
	}
}
