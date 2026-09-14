package com.saga.be.entity.github;

import com.saga.be.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "git_commit_branch",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_git_commit_branch", columnNames = {"git_commit_id", "branch_name"})
	}
)
public class GitCommitBranch extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "git_commit_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private GitCommit commit;

	@Column(name = "branch_name", length = 255, nullable = false)
	private String branchName;
}
