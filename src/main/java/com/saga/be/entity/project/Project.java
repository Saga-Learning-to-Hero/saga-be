package com.saga.be.entity.project;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.project.ProjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "project",
	indexes = {
		@Index(name = "ix_project_course", columnList = "course_id")
	}
)
public class Project extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "project_type_id", nullable = true)
	private ProjectType projectType;

	@Column(name = "name", length = 255, nullable = false)
	private String name;

	@Column(name = "description", columnDefinition = "MEDIUMTEXT")
	private String description;

	@Column(name = "repository_url", length = 500)
	private String repositoryUrl;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "created_by_user_id", nullable = true)
	private UserAccount createdBy;
}
