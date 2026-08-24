package com.saga.be.entity.assessment;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.enums.AssessmentRunStatus;
import com.saga.be.entity.enums.AssessmentRunType;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "assessment_run",
	indexes = {
		@Index(name = "ix_assessment_run_course_sprint", columnList = "course_id, sprint_id")
	}
)
public class AssessmentRun extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "project_id", nullable = true)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "sprint_id", nullable = true)
	private Sprint sprint;

	@Enumerated(EnumType.STRING)
	@Column(name = "run_type", length = 32, nullable = false)
	private AssessmentRunType runType;

	@Column(name = "calculation_version", length = 64)
	private String calculationVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 32, nullable = false)
	private AssessmentRunStatus status;

	@Column(name = "started_at")
	private LocalDateTime startedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;
}
