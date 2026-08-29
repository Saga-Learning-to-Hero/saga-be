package com.saga.be.entity.academic;

import com.saga.be.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "academic_class",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_academic_class_semester_code", columnNames = {"semester_id", "class_code"}),
		@UniqueConstraint(name = "uk_academic_class_id_semester", columnNames = {"id", "semester_id"})
	},
	indexes = {@Index(name = "ix_academic_class_semester", columnList = "semester_id")}
)
public class AcademicClass extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "semester_id", foreignKey = @jakarta.persistence.ForeignKey(name = "fk_academic_class_semester"))
	private Semester semester;

	@Column(name = "class_code", length = 64, nullable = false)
	private String classCode;

	@Column(name = "name", length = 255, nullable = false)
	private String name;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;
}
