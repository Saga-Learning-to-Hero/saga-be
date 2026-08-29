package com.saga.be.entity.academic;

import com.saga.be.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
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
	name = "syllabus_expected_activity",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_syllabus_activity_code", columnNames = {"syllabus_version_id", "code"}),
		@UniqueConstraint(name = "uk_syllabus_activity_phase_order", columnNames = {"phase_id", "order_index"})
	}
)
public class SyllabusExpectedActivity extends BaseEntity {

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "syllabus_version_id", columnDefinition = "char(36)", nullable = false)
	private UUID syllabusVersionId;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "phase_id", columnDefinition = "char(36)", nullable = false)
	private UUID phaseId;

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
						name = "phase_id",
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
	private SyllabusPhase phase;

	@Column(name = "code", length = 64, nullable = false)
	private String code;

	@Column(name = "name", length = 255, nullable = false)
	private String name;

	@Column(name = "description", columnDefinition = "TEXT")
	private String description;

	@Column(name = "order_index", nullable = false)
	private Integer orderIndex;

	public void setSyllabusVersion(SubjectSyllabusVersion syllabusVersion) {
		this.syllabusVersion = syllabusVersion;
		this.syllabusVersionId = syllabusVersion == null ? null : syllabusVersion.getId();
	}

	public void setPhase(SyllabusPhase phase) {
		this.phase = phase;
		this.phaseId = phase == null ? null : phase.getId();
	}
}
