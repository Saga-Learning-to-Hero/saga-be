package com.saga.be.dto.project;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskEvidenceItem(
		TaskEvidenceType type,
		UUID id,
		String title,
		LocalDateTime createdAt,
		String source,
		CommitEvidence commit,
		FileEvidence file,
		WebLinkEvidence webLink) {

	public static TaskEvidenceItem commit(UUID gitCommitId, String title, LocalDateTime createdAt, CommitEvidence commit) {
		return new TaskEvidenceItem(TaskEvidenceType.COMMIT, gitCommitId, title, createdAt, null, commit, null, null);
	}

	public static TaskEvidenceItem file(
			UUID fileId, String title, LocalDateTime createdAt, String source, FileEvidence file) {
		return new TaskEvidenceItem(TaskEvidenceType.FILE, fileId, title, createdAt, source, null, file, null);
	}

	public static TaskEvidenceItem webLink(
			UUID linkId, String title, LocalDateTime createdAt, String source, WebLinkEvidence webLink) {
		return new TaskEvidenceItem(TaskEvidenceType.WEB_LINK, linkId, title, createdAt, source, null, null, webLink);
	}

	public record CommitEvidence(
			UUID gitCommitId,
			String sha,
			String shortSha,
			String message,
			LocalDateTime committedAt,
			String repositoryFullName) {}

	public record FileEvidence(String filename, String mimeType, long sizeBytes, String downloadPath) {}

	public record WebLinkEvidence(String url, String title) {}
}
