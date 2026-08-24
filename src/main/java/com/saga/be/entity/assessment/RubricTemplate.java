package com.saga.be.entity.assessment;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Subject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "rubric_template")
public class RubricTemplate extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "subject_id", nullable = true)
	private Subject subject;

	@Column(name = "criteria_name", length = 255)
	private String criteriaName;

	@Column(name = "weight", precision = 10, scale = 4)
	private BigDecimal weight;

	@Column(name = "description", length = 1000)
	private String description;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;
}
