package com.saga.be.realtime;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory user-scoped SSE. Used to push {@code ACCOUNT_DISABLED} after a hard ban.
 * Emitters are process-local; other replicas do not receive this event. Redis session
 * revocation and request-time account-status enforcement remain global.
 */
@Component
public class UserSseHub {

	public static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
	public static final String READY = "READY";

	private static final Logger log = LoggerFactory.getLogger(UserSseHub.class);
	private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

	private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

	public SseEmitter subscribe(UUID userId) {
		SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
		emitters.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>()).add(emitter);
		emitter.onCompletion(() -> remove(userId, emitter));
		emitter.onTimeout(() -> remove(userId, emitter));
		emitter.onError(ex -> remove(userId, emitter));
		try {
			emitter.send(SseEmitter.event()
					.name(READY)
					.data(
							Map.of("type", READY, "occurredAt", Instant.now().toString()),
							MediaType.APPLICATION_JSON)
					.reconnectTime(3000L));
		} catch (IOException ex) {
			remove(userId, emitter);
			emitter.completeWithError(ex);
		}
		return emitter;
	}

	public void notifyDisabled(UUID userId, Instant occurredAt) {
		if (userId == null) {
			return;
		}
		CopyOnWriteArrayList<SseEmitter> live = emitters.remove(userId);
		if (live == null || live.isEmpty()) {
			return;
		}
		Map<String, Object> payload = Map.of(
				"type",
				ACCOUNT_DISABLED,
				"occurredAt",
				(occurredAt == null ? Instant.now() : occurredAt).toString());
		for (SseEmitter emitter : live) {
			try {
				emitter.send(SseEmitter.event()
						.name(ACCOUNT_DISABLED)
						.data(payload, MediaType.APPLICATION_JSON));
			} catch (Exception ex) {
				log.debug("user sse send failed");
			}
			try {
				emitter.complete();
			} catch (Exception ignored) {
				// already completed
			}
		}
	}

	@Scheduled(fixedDelayString = "${saga.realtime.heartbeat-ms:25000}")
	public void heartbeat() {
		for (Map.Entry<UUID, CopyOnWriteArrayList<SseEmitter>> entry : emitters.entrySet()) {
			for (SseEmitter emitter : List.copyOf(entry.getValue())) {
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

	void remove(UUID userId, SseEmitter emitter) {
		CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
		if (list == null) {
			return;
		}
		list.remove(emitter);
		if (list.isEmpty()) {
			emitters.remove(userId, list);
		}
	}

	int subscriberCount(UUID userId) {
		CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
		return list == null ? 0 : list.size();
	}
}
