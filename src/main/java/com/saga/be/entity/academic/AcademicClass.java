package com.saga.be.entity.academic;

import com.saga.be.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
		@UniqueConstraint(name = "uk_academic_class_code", columnNames = {"class_code"})
	}
)
public class AcademicClass extends BaseEntity {

	@Column(name = "class_code", length = 64, nullable = false)
	private String classCode;

	@Column(name = "name", length = 255, nullable = false)
	private String name;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;
}
