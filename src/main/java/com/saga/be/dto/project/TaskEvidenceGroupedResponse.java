package com.saga.be.dto.project;

import java.util.UUID;

public record TaskEvidenceGroupedResponse(UUID taskId, Groups groups) implements TaskEvidenceResponse {

	public record Groups(TaskEvidenceGroup COMMIT, TaskEvidenceGroup FILE, TaskEvidenceGroup WEB_LINK) {}
}
