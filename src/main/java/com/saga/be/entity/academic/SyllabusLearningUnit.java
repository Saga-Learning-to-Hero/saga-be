package com.saga.be.entity.academic;

import com.saga.be.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
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
	name = "syllabus_learning_unit",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_syllabus_unit_id_version", columnNames = {"id", "syllabus_version_id"}),
		@UniqueConstraint(name = "uk_syllabus_unit_code", columnNames = {"syllabus_version_id", "code"}),
		@UniqueConstraint(name = "uk_syllabus_unit_order", columnNames = {"syllabus_version_id", "order_index"})
	}
)
public class SyllabusLearningUnit extends BaseEntity {

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "syllabus_version_id", columnDefinition = "char(36)", nullable = false)
	private UUID syllabusVersionId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "syllabus_version_id",
			nullable = false,
			insertable = false,
			updatable = false,
			foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private SubjectSyllabusVersion syllabusVersion;

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
}
