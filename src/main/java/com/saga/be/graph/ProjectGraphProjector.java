package com.saga.be.graph;

import com.saga.be.realtime.ProjectRealtimeEvent;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the Neo4j project graph off the GET path. Mutations mark the project dirty;
 * GET joins a single in-flight rebuild when needed, otherwise only reads Neo4j.
 */
@Component
@Profile("!test")
public class ProjectGraphProjector {

	private static final Logger log = LoggerFactory.getLogger(ProjectGraphProjector.class);
	private static final Set<ProjectRealtimeEventType> INVALIDATING = Set.of(
			ProjectRealtimeEventType.TASKS_CHANGED,
			ProjectRealtimeEventType.SPRINTS_CHANGED,
			ProjectRealtimeEventType.COMMITS_CHANGED,
			ProjectRealtimeEventType.TASK_LINKS_CHANGED,
			ProjectRealtimeEventType.TASK_EVIDENCE_CHANGED,
			ProjectRealtimeEventType.PEER_REVIEW_CHANGED);

	private final ProjectGraphLoader loader;
	private final ProjectGraphWriter writer;
	private final SagaGraphClient client;
	private final ProjectRealtimePublisher realtime;
	private final long debounceMs;
	private final ConcurrentHashMap<UUID, Boolean> dirty = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, ConcurrentSkipListSet<String>> reasons = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, AtomicLong> revisions = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, CompletableFuture<Long>> inFlight = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, ScheduledFuture<?>> debounce = new ConcurrentHashMap<>();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "graph-debounce");
		thread.setDaemon(true);
		return thread;
	});
	private final ExecutorService rebuilds = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "graph-rebuild");
		thread.setDaemon(true);
		return thread;
	});

	public ProjectGraphProjector(
			ProjectGraphLoader loader,
			ProjectGraphWriter writer,
			SagaGraphClient client,
			ProjectRealtimePublisher realtime,
			@Value("${saga.graph.rebuild-debounce-ms:400}") long debounceMs) {
		this.loader = loader;
		this.writer = writer;
		this.client = client;
		this.realtime = realtime;
		this.debounceMs = Math.max(0L, debounceMs);
	}

	@EventListener
	public void onProjectEvent(ProjectRealtimeEvent event) {
		if (event == null || event.projectId() == null || !INVALIDATING.contains(event.type())) {
			return;
		}
		markDirty(event.projectId(), event.type().name());
	}

	public void markDirty(UUID projectId, String reason) {
		if (projectId == null) {
			return;
		}
		dirty.put(projectId, Boolean.TRUE);
		reasons.computeIfAbsent(projectId, id -> new ConcurrentSkipListSet<>()).add(reason == null ? "UNKNOWN" : reason);
		ScheduledFuture<?> previous = debounce.get(projectId);
		if (previous != null) {
			previous.cancel(false);
		}
		if (debounceMs == 0L) {
			rebuildAsync(projectId);
			return;
		}
		debounce.put(projectId, scheduler.schedule(() -> rebuildAsync(projectId), debounceMs, TimeUnit.MILLISECONDS));
	}

	public long ensureFresh(UUID projectId) {
		CompletableFuture<Long> flying = inFlight.get(projectId);
		if (flying != null && !flying.isDone()) {
			return flying.join();
		}
		if (Boolean.TRUE.equals(dirty.get(projectId)) || !client.projectExists(projectId)) {
			return rebuildAsync(projectId).join();
		}
		return revisions.computeIfAbsent(projectId, id -> new AtomicLong(0L)).get();
	}

	public long revision(UUID projectId) {
		AtomicLong value = revisions.get(projectId);
		return value == null ? 0L : value.get();
	}

	public static String etag(UUID projectId, long revision) {
		return etag(projectId, revision, "");
	}

	public static String etag(UUID projectId, long revision, String viewKey) {
		String base = "graph-" + projectId + "-" + revision;
		String hash = GraphRead.viewKeyHash(viewKey);
		return hash.isEmpty() ? base : base + "-" + hash;
	}

	private CompletableFuture<Long> rebuildAsync(UUID projectId) {
		CompletableFuture<Long> created = new CompletableFuture<>();
		CompletableFuture<Long> current = inFlight.compute(projectId, (id, existing) -> {
			if (existing != null && !existing.isDone()) {
				return existing;
			}
			return created;
		});
		if (current != created) {
			return current;
		}
		rebuilds.execute(() -> {
			try {
				created.complete(doRebuild(projectId));
			} catch (RuntimeException ex) {
				created.completeExceptionally(ex);
			} finally {
				inFlight.remove(projectId, created);
			}
		});
		return created;
	}

	private long doRebuild(UUID projectId) {
		boolean dirtyNow = dirty.remove(projectId) != null;
		Set<String> reasonSet = reasons.remove(projectId);
		if (!dirtyNow && client.projectExists(projectId)) {
			return revisions.computeIfAbsent(projectId, id -> new AtomicLong(0L)).get();
		}
		long started = System.nanoTime();
		try {
			writer.rebuild(loader.load(projectId));
			long revision = revisions.computeIfAbsent(projectId, id -> new AtomicLong(0L)).incrementAndGet();
			String reason = reasonSet == null || reasonSet.isEmpty() ? "REBUILD" : String.join(",", reasonSet);
			realtime.publish(ProjectRealtimeEvent.graphChanged(projectId, revision, reason));
			log.info(
					"graph rebuilt projectId={} revision={} elapsedMs={} reason={}",
					projectId,
					revision,
					(System.nanoTime() - started) / 1_000_000L,
					reason);
			if (Boolean.TRUE.equals(dirty.get(projectId))) {
				rebuildAsync(projectId);
			}
			return revision;
		} catch (RuntimeException ex) {
			dirty.put(projectId, Boolean.TRUE);
			if (reasonSet != null) {
				reasons.computeIfAbsent(projectId, id -> new ConcurrentSkipListSet<>()).addAll(reasonSet);
			}
			log.error("graph rebuild failed projectId={}", projectId, ex);
			throw ex;
		}
	}

	@PreDestroy
	void shutdown() {
		scheduler.shutdownNow();
		rebuilds.shutdownNow();
	}
}
