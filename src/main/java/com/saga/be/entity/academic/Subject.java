package com.saga.be.entity.academic;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.SubjectStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

	@Column(name = "name_vietnamese", length = 255)
	private String nameVietnamese;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 32, nullable = false)
	private SubjectStatus status = SubjectStatus.ACTIVE;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;
}
