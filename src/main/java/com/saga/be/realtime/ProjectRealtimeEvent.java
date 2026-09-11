package com.saga.be.realtime;

import java.time.Instant;
import java.util.UUID;

public record ProjectRealtimeEvent(
		ProjectRealtimeEventType type, UUID projectId, String entityId, Instant occurredAt) {

	public static ProjectRealtimeEvent of(ProjectRealtimeEventType type, UUID projectId) {
		return new ProjectRealtimeEvent(type, projectId, null, Instant.now());
	}

	public static ProjectRealtimeEvent of(ProjectRealtimeEventType type, UUID projectId, String entityId) {
		return new ProjectRealtimeEvent(type, projectId, entityId, Instant.now());
	}
}
