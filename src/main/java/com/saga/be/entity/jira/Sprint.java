package com.saga.be.entity.jira;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.jira.JiraIntegration;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
	name = "sprint",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_sprint_external", columnNames = {"jira_integration_id", "external_sprint_id"})
	}
)
public class Sprint extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "jira_integration_id", nullable = false)
	private JiraIntegration jiraIntegration;

	@Column(name = "name", length = 255)
	private String name;

	@Column(name = "external_sprint_id", length = 128)
	private String externalSprintId;

	@Column(name = "start_date")
	private LocalDateTime startDate;

	@Column(name = "end_date")
	private LocalDateTime endDate;

	@Column(name = "goal", length = 1000)
	private String goal;

	@Column(name = "state", length = 64)
	private String state;

	@Column(name = "complete_date")
	private LocalDateTime completeDate;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;
}
