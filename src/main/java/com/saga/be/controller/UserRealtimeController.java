package com.saga.be.controller;

import com.saga.be.realtime.UserSseHub;
import com.saga.be.security.SagaUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Profile("!test")
@RequestMapping("/api/users/me/events")
@Tag(
		name = "User realtime",
		description =
				"Authenticated user-scoped SSE on this instance. READY, NOTIFICATION_CREATED, and ACCOUNT_DISABLED. Process-local; not cross-replica.")
@SecurityRequirement(name = "SAGA_SESSION")
public class UserRealtimeController {

	private final UserSseHub hub;

	public UserRealtimeController(UserSseHub hub) {
		this.hub = hub;
	}

	@GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@Operation(summary = "Subscribe to the current user's realtime events. No userId in the path.")
	public SseEmitter subscribe(@AuthenticationPrincipal SagaUserPrincipal principal) {
		return hub.subscribe(principal.getUserId());
	}
}
