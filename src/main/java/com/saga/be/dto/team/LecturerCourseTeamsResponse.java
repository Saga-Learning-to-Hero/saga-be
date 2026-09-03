package com.saga.be.dto.team;

import java.util.List;
import java.util.UUID;

public record LecturerCourseTeamsResponse(UUID courseId, List<LecturerTeamResponse> teams) {}
