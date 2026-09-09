package com.saga.be.entity.jira;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.EvidenceSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
	name = "task_web_link",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_task_web_link_hash", columnNames = {"task_id", "url_hash"}),
		@UniqueConstraint(name = "uk_task_web_link_external", columnNames = {"task_id", "external_id"})
	},
	indexes = {@Index(name = "ix_task_web_link_task", columnList = "task_id")}
)
public class TaskWebLink extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "task_id", nullable = false)
	private Task task;

	@Column(name = "url", length = 2048, nullable = false)
	private String url;

	@Column(name = "url_hash", length = 64, nullable = false)
	private String urlHash;

	@Column(name = "title", length = 255)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", length = 16, nullable = false)
	private EvidenceSource source = EvidenceSource.SAGA;

	@Column(name = "external_id", length = 64)
	private String externalId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_user_id")
	private UserAccount createdBy;
}
