package com.saga.be.controller;

import com.saga.be.realtime.ProjectSseHub;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.projection.ProjectDataAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Profile("!test")
@RequestMapping("/api/projects/{projectId}/events")
@Tag(name = "Project realtime", description = "Project-scoped SSE invalidation stream.")
@SecurityRequirement(name = "SAGA_SESSION")
public class ProjectRealtimeController {

	private final ProjectDataAuthorization authorization;
	private final ProjectSseHub hub;

	public ProjectRealtimeController(ProjectDataAuthorization authorization, ProjectSseHub hub) {
		this.authorization = authorization;
		this.hub = hub;
	}

	@GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@Operation(summary = "Subscribe to project realtime invalidation events. Refetch REST after each event.")
	public SseEmitter subscribe(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		authorization.requireReader(principal.getUserId(), projectId);
		return hub.subscribe(projectId);
	}
}
