package com.saga.be.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.realtime.ProjectRealtimeEvent;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
class ProjectGraphProjectorTest {

	@Mock
	private ProjectGraphLoader loader;
	@Mock
	private ProjectGraphWriter writer;
	@Mock
	private SagaGraphClient client;
	@Mock
	private ProjectRealtimePublisher realtime;

	private ProjectGraphProjector projector;
	private UUID projectId;

	@BeforeEach
	void setUp() {
		projectId = UUID.randomUUID();
		projector = new ProjectGraphProjector(loader, writer, client, realtime, 0L);
	}

	@AfterEach
	void tearDown() {
		projector.shutdown();
	}

	@Test
	void ensureFresh_skipsRebuildWhenProjectionExistsAndClean() {
		when(client.projectExists(projectId)).thenReturn(true);
		assertThat(projector.ensureFresh(projectId)).isZero();
		verify(writer, never()).rebuild(any());
		verify(realtime, never()).publish(any(ProjectRealtimeEvent.class));
	}

	@Test
	void ensureFresh_coldRebuildOnceAndPublishesGraphChanged() {
		when(client.projectExists(projectId)).thenReturn(false, false, true);
		when(loader.load(projectId)).thenReturn(snapshot());
		long revision = projector.ensureFresh(projectId);
		assertThat(revision).isEqualTo(1L);
		verify(writer, times(1)).rebuild(any());
		ArgumentCaptor<ProjectRealtimeEvent> captor = ArgumentCaptor.forClass(ProjectRealtimeEvent.class);
		verify(realtime).publish(captor.capture());
		assertThat(captor.getValue().type()).isEqualTo(ProjectRealtimeEventType.GRAPH_CHANGED);
		assertThat(captor.getValue().revision()).isEqualTo("1");
		assertThat(projector.ensureFresh(projectId)).isEqualTo(1L);
		verify(writer, times(1)).rebuild(any());
	}

	@Test
	void concurrentEnsureFresh_singleRebuild() throws Exception {
		when(client.projectExists(projectId)).thenReturn(false);
		when(loader.load(projectId)).thenReturn(snapshot());
		CountDownLatch started = new CountDownLatch(1);
		doAnswer((Answer<Void>) invocation -> {
			started.countDown();
			Thread.sleep(80);
			return null;
		})
				.when(writer)
				.rebuild(any());
		AtomicInteger seen = new AtomicInteger();
		Thread a = new Thread(() -> {
			projector.ensureFresh(projectId);
			seen.incrementAndGet();
		});
		Thread b = new Thread(() -> {
			projector.ensureFresh(projectId);
			seen.incrementAndGet();
		});
		a.start();
		b.start();
		assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
		a.join(2000);
		b.join(2000);
		assertThat(seen.get()).isEqualTo(2);
		verify(writer, times(1)).rebuild(any());
	}

	@Test
	void mutationMarksDirtyThenGetRebuilds() {
		when(client.projectExists(projectId)).thenReturn(true);
		when(loader.load(projectId)).thenReturn(snapshot());
		projector.ensureFresh(projectId);
		projector.onProjectEvent(ProjectRealtimeEvent.of(ProjectRealtimeEventType.TASKS_CHANGED, projectId));
		assertThat(projector.ensureFresh(projectId)).isEqualTo(1L);
		verify(writer, times(1)).rebuild(any());
	}

	private ProjectGraphSnapshot snapshot() {
		return new ProjectGraphSnapshot(
				projectId, "demo", null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
	}
}
