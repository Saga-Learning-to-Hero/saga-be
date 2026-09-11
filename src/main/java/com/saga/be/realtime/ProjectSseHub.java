package com.saga.be.realtime;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory project-scoped SSE fan-out. Single backend instance only.
 */
@Component
public class ProjectSseHub {

	private static final Logger log = LoggerFactory.getLogger(ProjectSseHub.class);
	private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

	private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

	public SseEmitter subscribe(UUID projectId) {
		SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
		emitters.computeIfAbsent(projectId, id -> new CopyOnWriteArrayList<>()).add(emitter);
		emitter.onCompletion(() -> remove(projectId, emitter));
		emitter.onTimeout(() -> remove(projectId, emitter));
		emitter.onError(ex -> remove(projectId, emitter));
		try {
			emitter.send(SseEmitter.event()
					.name(ProjectRealtimeEventType.READY.name())
					.data(
							Map.of(
									"type",
									ProjectRealtimeEventType.READY.name(),
									"projectId",
									projectId.toString(),
									"occurredAt",
									java.time.Instant.now().toString()),
							MediaType.APPLICATION_JSON)
					.reconnectTime(3000L));
		} catch (IOException ex) {
			remove(projectId, emitter);
			emitter.completeWithError(ex);
		}
		return emitter;
	}

	@EventListener
	public void onProjectEvent(ProjectRealtimeEvent event) {
		if (event == null || event.projectId() == null || event.type() == ProjectRealtimeEventType.READY) {
			return;
		}
		List<SseEmitter> live = emitters.get(event.projectId());
		if (live == null || live.isEmpty()) {
			return;
		}
		Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("type", event.type().name());
		payload.put("projectId", event.projectId().toString());
		if (event.entityId() != null) {
			payload.put("entityId", event.entityId());
		}
		payload.put("occurredAt", event.occurredAt() == null ? java.time.Instant.now().toString() : event.occurredAt().toString());
		for (SseEmitter emitter : live) {
			try {
				emitter.send(SseEmitter.event()
						.name(event.type().name())
						.data(payload, MediaType.APPLICATION_JSON)
						.reconnectTime(3000L));
			} catch (Exception ex) {
				remove(event.projectId(), emitter);
				try {
					emitter.complete();
				} catch (Exception ignored) {
					// ignore
				}
			}
		}
	}

	@Scheduled(fixedDelayString = "${saga.realtime.heartbeat-ms:25000}")
	public void heartbeat() {
		for (Map.Entry<UUID, CopyOnWriteArrayList<SseEmitter>> entry : emitters.entrySet()) {
			for (SseEmitter emitter : entry.getValue()) {
				try {
					emitter.send(SseEmitter.event().comment("heartbeat"));
				} catch (Exception ex) {
					remove(entry.getKey(), emitter);
					try {
						emitter.complete();
					} catch (Exception ignored) {
						// ignore
					}
				}
			}
		}
	}

	void remove(UUID projectId, SseEmitter emitter) {
		CopyOnWriteArrayList<SseEmitter> list = emitters.get(projectId);
		if (list == null) {
			return;
		}
		list.remove(emitter);
		if (list.isEmpty()) {
			emitters.remove(projectId, list);
		}
	}

	int subscriberCount(UUID projectId) {
		CopyOnWriteArrayList<SseEmitter> list = emitters.get(projectId);
		return list == null ? 0 : list.size();
	}
}
