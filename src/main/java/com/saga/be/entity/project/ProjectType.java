package com.saga.be.entity.project;

import com.saga.be.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "project_type",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_project_type_code", columnNames = {"code"})
	}
)
public class ProjectType extends BaseEntity {

	@Column(name = "code", length = 64, nullable = false)
	private String code;

	@Column(name = "name", length = 255, nullable = false)
	private String name;

	@Column(name = "description", length = 1000)
	private String description;

	@Column(name = "criteria_config", columnDefinition = "TEXT")
	private String criteriaConfig;
}
