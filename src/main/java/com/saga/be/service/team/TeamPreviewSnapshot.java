package com.saga.be.service.team;

import com.saga.be.dto.team.TeamPreviewRow;
import java.util.List;
import java.util.UUID;

public record TeamPreviewSnapshot(
		UUID actorUserId, UUID courseId, String classCode, List<String> blockingErrors, List<TeamPreviewRow> rows) {}
