package com.saga.be.entity.academic;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.enums.ContributionConfigMode;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "course",
	uniqueConstraints = {@UniqueConstraint(name = "uk_course_class_subject", columnNames = {"academic_class_id", "subject_id"})},
	indexes = {
		@Index(name = "ix_course_semester_instructor", columnList = "semester_id, instructor_id"),
		@Index(name = "ix_course_subject", columnList = "subject_id"),
		@Index(name = "ix_course_class", columnList = "academic_class_id")
	}
)
public class Course extends BaseEntity {

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "syllabus_version_id", columnDefinition = "char(36)")
	private UUID syllabusVersionId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "subject_id", nullable = false)
	private Subject subject;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(
			value = {
				@JoinColumn(
						name = "syllabus_version_id",
						referencedColumnName = "id",
						insertable = false,
						updatable = false),
				@JoinColumn(
						name = "subject_id",
						referencedColumnName = "subject_id",
						insertable = false,
						updatable = false)
			},
			foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private SubjectSyllabusVersion syllabusVersion;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "academic_class_id", nullable = false)
	private AcademicClass academicClass;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "semester_id", nullable = false)
	private Semester semester;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "instructor_id")
	private LecturerProfile instructor;

	@Column(name = "course_code", length = 64)
	private String courseCode;

	@Column(name = "name", length = 255, nullable = false)
	private String name;

	@Column(name = "code_contribution_weight", nullable = false)
	private Double codeContributionWeight = 25d;

	@Column(name = "test_contribution_weight", nullable = false)
	private Double testContributionWeight = 25d;

	@Column(name = "document_contribution_weight", nullable = false)
	private Double documentContributionWeight = 25d;

	@Column(name = "research_contribution_weight", nullable = false)
	private Double researchContributionWeight = 25d;

	@Enumerated(EnumType.STRING)
	@Column(name = "contribution_config_mode", length = 32, nullable = false)
	private ContributionConfigMode contributionConfigMode = ContributionConfigMode.COURSE;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	public void setSyllabusVersion(SubjectSyllabusVersion syllabusVersion) {
		this.syllabusVersion = syllabusVersion;
		this.syllabusVersionId = syllabusVersion == null ? null : syllabusVersion.getId();
	}
}
