package com.saga.be.entity.project;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
	name = "team",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_team_project", columnNames = {"project_id"}),
		@UniqueConstraint(name = "uk_team_id_course", columnNames = {"id", "course_id"}),
		@UniqueConstraint(name = "uk_team_course_team_no", columnNames = {"course_id", "team_no"})
	},
	indexes = {
		@Index(name = "ix_team_course", columnList = "course_id")
	}
)
public class Team extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "project_id")
	private Project project;

	@Column(name = "team_no", nullable = false)
	private Integer teamNo;

	@Column(name = "name", length = 255, nullable = false)
	private String name;
}
