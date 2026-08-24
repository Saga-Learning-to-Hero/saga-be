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
	name = "semester",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_semester_code", columnNames = {"code"})
	}
)
public class Semester extends BaseEntity {

	@Column(name = "code", length = 64, nullable = false)
	private String code;

	@Column(name = "name", length = 255, nullable = false)
	private String name;

	@Column(name = "start_date")
	private LocalDateTime startDate;

	@Column(name = "end_date")
	private LocalDateTime endDate;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;
}
