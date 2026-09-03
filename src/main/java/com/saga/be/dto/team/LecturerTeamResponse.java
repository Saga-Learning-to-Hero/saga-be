package com.saga.be.dto.team;

import java.util.List;
import java.util.UUID;

public record LecturerTeamResponse(
		UUID teamId, int teamNo, String teamName, UUID projectId, List<LecturerTeamMemberResponse> members) {}
