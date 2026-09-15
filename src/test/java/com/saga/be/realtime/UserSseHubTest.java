package com.saga.be.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class UserSseHubTest {

	@Test
	void notifyDisabledRemovesEmittersAfterAccountDisabled() {
		UserSseHub hub = new UserSseHub();
		UUID userId = UUID.randomUUID();
		SseEmitter emitter = hub.subscribe(userId);
		assertThat(hub.subscriberCount(userId)).isEqualTo(1);
		hub.notifyDisabled(userId, Instant.parse("2026-09-15T12:00:00Z"));
		assertThat(hub.subscriberCount(userId)).isEqualTo(0);
		assertThat(emitter).isNotNull();
	}

	@Test
	void projectHubClosesOnlyTheBannedUser() {
		ProjectSseHub hub = new ProjectSseHub();
		UUID projectId = UUID.randomUUID();
		UUID banned = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		hub.subscribe(projectId, banned);
		hub.subscribe(projectId, other);
		assertThat(hub.subscriberCount(projectId)).isEqualTo(2);
		hub.closeForUser(banned);
		assertThat(hub.subscriberCount(projectId)).isEqualTo(1);
	}
}
