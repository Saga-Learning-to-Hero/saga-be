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
	name = "subject",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_subject_code", columnNames = {"subject_code"})
	}
)
public class Subject extends BaseEntity {

	@Column(name = "subject_code", length = 64, nullable = false)
	private String subjectCode;

	@Column(name = "name", length = 255, nullable = false)
	private String name;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;
}
