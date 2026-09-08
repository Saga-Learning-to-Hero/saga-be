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
	name = "task_file",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_task_file_hash", columnNames = {"task_id", "content_hash"}),
		@UniqueConstraint(name = "uk_task_file_external", columnNames = {"task_id", "external_id"})
	},
	indexes = {@Index(name = "ix_task_file_task", columnList = "task_id")}
)
public class TaskFile extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "task_id", nullable = false)
	private Task task;

	@Column(name = "original_filename", length = 512, nullable = false)
	private String originalFilename;

	@Column(name = "mime_type", length = 255, nullable = false)
	private String mimeType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@Column(name = "content_hash", length = 64, nullable = false)
	private String contentHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", length = 16, nullable = false)
	private EvidenceSource source = EvidenceSource.SAGA;

	@Column(name = "external_id", length = 64)
	private String externalId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_user_id")
	private UserAccount createdBy;
}
