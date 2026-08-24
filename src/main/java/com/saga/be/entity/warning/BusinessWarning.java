package com.saga.be.entity.warning;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.SprintProgressMode;
import com.saga.be.entity.enums.WarningCategory;
import com.saga.be.entity.enums.WarningSeverity;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "business_warning",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_business_warning_event", columnNames = {"event_key"})
	},
	indexes = {
		@Index(name = "ix_business_warning_course", columnList = "course_id")
	}
)
public class BusinessWarning extends BaseEntity {

	@Column(name = "warning_type", length = 64, nullable = false)
	private String warningType;

	@Enumerated(EnumType.STRING)
	@Column(name = "category", length = 32, nullable = false)
	private WarningCategory category;

	@Column(name = "event_key", length = 255, nullable = false)
	private String eventKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "severity", length = 32)
	private WarningSeverity severity;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "course_id", nullable = true)
	private Course course;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "team_id", nullable = true)
	private Team team;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "project_id", nullable = true)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "sprint_id", nullable = true)
	private Sprint sprint;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "student_profile_id", nullable = true)
	private StudentProfile studentProfile;

	@Column(name = "commit_sha", length = 64)
	private String commitSha;

	@Column(name = "evidence_summary", length = 1000, nullable = false)
	private String evidenceSummary;

	@Enumerated(EnumType.STRING)
	@Column(name = "progress_mode", length = 32)
	private SprintProgressMode progressMode;
}
