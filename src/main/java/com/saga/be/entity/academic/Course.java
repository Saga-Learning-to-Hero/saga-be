package com.saga.be.entity.academic;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.enums.ContributionConfigMode;
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
	name = "course",
	indexes = {
		@Index(name = "ix_course_semester_instructor", columnList = "semester_id, instructor_id"),
		@Index(name = "ix_course_subject", columnList = "subject_id"),
		@Index(name = "ix_course_class", columnList = "academic_class_id")
	}
)
public class Course extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "subject_id", nullable = false)
	private Subject subject;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "academic_class_id", nullable = false)
	private AcademicClass academicClass;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "semester_id", nullable = false)
	private Semester semester;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "instructor_id", nullable = true)
	private LecturerProfile instructor;

	@Column(name = "course_code", length = 64)
	private String courseCode;

	@Column(name = "name", length = 255, nullable = false)
	private String name;

	@Column(name = "code_contribution_weight", nullable = false)
	private Double codeContributionWeight;

	@Column(name = "test_contribution_weight", nullable = false)
	private Double testContributionWeight;

	@Column(name = "document_contribution_weight", nullable = false)
	private Double documentContributionWeight;

	@Column(name = "research_contribution_weight", nullable = false)
	private Double researchContributionWeight;

	@Enumerated(EnumType.STRING)
	@Column(name = "contribution_config_mode", length = 32, nullable = false)
	private ContributionConfigMode contributionConfigMode;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;
}
