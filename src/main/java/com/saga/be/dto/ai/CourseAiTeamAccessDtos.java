package com.saga.be.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** Which teams of a course may fall back to the course (lecturer) AI key. */
public final class CourseAiTeamAccessDtos {

	private CourseAiTeamAccessDtos() {}

	public record TeamKey(boolean configured, String provider, String modelId, String status) {}

	public record TeamAccess(
			UUID teamId,
			Integer teamNo,
			String teamName,
			UUID projectId,
			String projectName,
			@Schema(description = "The team's own AI key (entered by its leader); the key itself is never returned") TeamKey teamKey,
			@Schema(description = "The lecturer lets this team use the course key when it has no usable key of its own") boolean courseKeyAllowed,
			@Schema(description = "Key AI work of this team uses right now: TEAM | COURSE | NONE") String effectiveKey) {}

	public record Response(
			@Schema(description = "The course has a usable PRIMARY course key") boolean courseKeyConfigured,
			@Schema(description = "Course AI automation is on (automatic reviews with the course key)") boolean automationEnabled,
			List<TeamAccess> teams) {}

	public record UpdateRequest(@Schema(description = "true = this team may use the course key") Boolean allowed) {}
}
