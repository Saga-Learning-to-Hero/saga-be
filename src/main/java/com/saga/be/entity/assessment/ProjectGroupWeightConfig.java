package com.saga.be.entity.assessment;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "project_group_weight_config",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_weight_config_project", columnNames = {"project_id"})
	}
)
public class ProjectGroupWeightConfig extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "team_id", nullable = false)
	private Team team;

	@Column(name = "code_weight", precision = 6, scale = 5, nullable = false)
	private BigDecimal codeWeight;

	@Column(name = "test_weight", precision = 6, scale = 5, nullable = false)
	private BigDecimal testWeight;

	@Column(name = "document_weight", precision = 6, scale = 5, nullable = false)
	private BigDecimal documentWeight;

	@Column(name = "research_weight", precision = 6, scale = 5, nullable = false)
	private BigDecimal researchWeight;

	@Column(name = "note", length = 1000)
	private String note;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "updated_by_user_id", nullable = true)
	private UserAccount updatedBy;
}
