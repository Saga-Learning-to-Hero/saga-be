package com.saga.be.entity.academic;

import com.saga.be.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.sql.Types;
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
	name = "syllabus_learning_unit_outcome",
	uniqueConstraints = {@UniqueConstraint(name = "uk_unit_lo", columnNames = {"learning_unit_id", "learning_outcome_id"})},
	indexes = {@Index(name = "ix_unit_lo_syllabus", columnList = "syllabus_version_id")}
)
public class SyllabusLearningUnitOutcome extends BaseEntity {

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "syllabus_version_id", columnDefinition = "char(36)", nullable = false)
	private UUID syllabusVersionId;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "learning_unit_id", columnDefinition = "char(36)", nullable = false)
	private UUID learningUnitId;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "learning_outcome_id", columnDefinition = "char(36)", nullable = false)
	private UUID learningOutcomeId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "syllabus_version_id",
			nullable = false,
			insertable = false,
			updatable = false,
			foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private SubjectSyllabusVersion syllabusVersion;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(
			value = {
				@JoinColumn(
						name = "learning_unit_id",
						referencedColumnName = "id",
						nullable = false,
						insertable = false,
						updatable = false),
				@JoinColumn(
						name = "syllabus_version_id",
						referencedColumnName = "syllabus_version_id",
						nullable = false,
						insertable = false,
						updatable = false)
			},
			foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private SyllabusLearningUnit learningUnit;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(
			value = {
				@JoinColumn(
						name = "learning_outcome_id",
						referencedColumnName = "id",
						nullable = false,
						insertable = false,
						updatable = false),
				@JoinColumn(
						name = "syllabus_version_id",
						referencedColumnName = "syllabus_version_id",
						nullable = false,
						insertable = false,
						updatable = false)
			},
			foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private SyllabusLearningOutcome learningOutcome;

	public void setSyllabusVersion(SubjectSyllabusVersion syllabusVersion) {
		this.syllabusVersion = syllabusVersion;
		this.syllabusVersionId = syllabusVersion == null ? null : syllabusVersion.getId();
	}

	public void setLearningUnit(SyllabusLearningUnit learningUnit) {
		this.learningUnit = learningUnit;
		this.learningUnitId = learningUnit == null ? null : learningUnit.getId();
	}

	public void setLearningOutcome(SyllabusLearningOutcome learningOutcome) {
		this.learningOutcome = learningOutcome;
		this.learningOutcomeId = learningOutcome == null ? null : learningOutcome.getId();
	}
}
