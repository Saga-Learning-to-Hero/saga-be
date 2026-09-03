package com.saga.be.dto.team;

import java.util.List;
import java.util.UUID;

public record StudentTeamResponse(
		UUID teamId, int teamNo, String teamName, String myRole, List<StudentTeamMemberResponse> members) {}
