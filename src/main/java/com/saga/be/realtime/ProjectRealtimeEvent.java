package com.saga.be.realtime;

import java.time.Instant;
import java.util.UUID;

public record ProjectRealtimeEvent(
		ProjectRealtimeEventType type,
		UUID projectId,
		String entityId,
		Instant occurredAt,
		String revision,
		String reason) {

	public static ProjectRealtimeEvent of(ProjectRealtimeEventType type, UUID projectId) {
		return new ProjectRealtimeEvent(type, projectId, null, Instant.now(), null, null);
	}

	public static ProjectRealtimeEvent of(ProjectRealtimeEventType type, UUID projectId, String entityId) {
		return new ProjectRealtimeEvent(type, projectId, entityId, Instant.now(), null, null);
	}

	public static ProjectRealtimeEvent graphChanged(UUID projectId, long revision, String reason) {
		return new ProjectRealtimeEvent(
				ProjectRealtimeEventType.GRAPH_CHANGED,
				projectId,
				null,
				Instant.now(),
				Long.toString(revision),
				reason);
	}
}
