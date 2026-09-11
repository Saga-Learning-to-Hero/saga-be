package com.saga.be.entity.github;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.project.Project;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Authoritative membership: SAGA project ↔ GitHub App installation.
 * One installation may belong to many projects; repository selection stays project-specific.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "github_project_installation",
	uniqueConstraints = {
		@UniqueConstraint(
				name = "uk_github_project_installation",
				columnNames = {"project_id", "github_installation_id"})
	}
)
public class GithubProjectInstallation extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "github_installation_id", nullable = false)
	private GithubInstallation installation;
}
