package com.saga.be.entity.academic;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.SyllabusStatus;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
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
	name = "subject_syllabus_version",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_syllabus_id_subject", columnNames = {"id", "subject_id"}),
		@UniqueConstraint(name = "uk_syllabus_subject_version_label", columnNames = {"subject_id", "version_label"}),
		@UniqueConstraint(name = "uk_syllabus_subject_external_id", columnNames = {"subject_id", "external_syllabus_id"})
	},
	indexes = {@Index(name = "ix_syllabus_subject_status", columnList = "subject_id, status")}
)
public class SubjectSyllabusVersion extends BaseEntity {

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "subject_id", columnDefinition = "char(36)", nullable = false)
	private UUID subjectId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "subject_id",
			nullable = false,
			insertable = false,
			updatable = false,
			foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private Subject subject;

	@Column(name = "external_syllabus_id", length = 64)
	private String externalSyllabusId;

	@Column(name = "version_label", length = 64, nullable = false)
	private String versionLabel;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 32, nullable = false)
	private SyllabusStatus status = SyllabusStatus.DRAFT;

	@Column(name = "title_english", length = 255)
	private String titleEnglish;

	@Column(name = "title_vietnamese", length = 255)
	private String titleVietnamese;

	@Column(name = "credits", precision = 6, scale = 2)
	private BigDecimal credits;

	@Column(name = "level", length = 64)
	private String level;

	@Column(name = "learning_teaching_method", columnDefinition = "TEXT")
	private String learningTeachingMethod;

	@Column(name = "time_allocation", columnDefinition = "TEXT")
	private String timeAllocation;

	@Column(name = "prerequisites", columnDefinition = "TEXT")
	private String prerequisites;

	@Column(name = "description", columnDefinition = "TEXT")
	private String description;

	@Column(name = "student_duties", columnDefinition = "TEXT")
	private String studentDuties;

	@Column(name = "tools", columnDefinition = "TEXT")
	private String tools;

	@Column(name = "textbooks", columnDefinition = "TEXT")
	private String textbooks;

	@Column(name = "reference_materials", columnDefinition = "TEXT")
	private String referenceMaterials;

	@Column(name = "grading_scale", columnDefinition = "TEXT")
	private String gradingScale;

	@Column(name = "published_at")
	private LocalDateTime publishedAt;

	public void setSubject(Subject subject) {
		this.subject = subject;
		this.subjectId = subject == null ? null : subject.getId();
	}
}
