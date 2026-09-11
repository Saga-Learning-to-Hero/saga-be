package com.saga.be.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class ProjectRealtimePublisherAndHubTest {

	@Mock
	private ApplicationEventPublisher events;

	@Test
	void publisher_withoutTransaction_publishesImmediately() {
		List<Object> published = new ArrayList<>();
		ProjectRealtimePublisher publisher = new ProjectRealtimePublisher(published::add);
		UUID projectId = UUID.randomUUID();
		publisher.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId);
		assertThat(published).hasSize(1);
		assertThat(((ProjectRealtimeEvent) published.getFirst()).type())
				.isEqualTo(ProjectRealtimeEventType.TASKS_CHANGED);
	}

	@Test
	void publisher_registersAfterCommit_andSkipsOnRollback() {
		List<Object> published = new ArrayList<>();
		ProjectRealtimePublisher publisher = new ProjectRealtimePublisher(published::add);
		UUID projectId = UUID.randomUUID();
		TransactionSynchronizationManager.initSynchronization();
		try {
			publisher.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId);
			assertThat(published).isEmpty();
			List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
			assertThat(syncs).hasSize(1);
			syncs.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
			assertThat(published).isEmpty();
			verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any());
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		TransactionSynchronizationManager.initSynchronization();
		try {
			publisher.publish(ProjectRealtimeEventType.COMMITS_CHANGED, projectId);
			TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
			assertThat(published).hasSize(1);
			assertThat(((ProjectRealtimeEvent) published.getFirst()).type())
					.isEqualTo(ProjectRealtimeEventType.COMMITS_CHANGED);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void hub_subscribeSendsReady_andCleansUpOnComplete() {
		ProjectSseHub hub = new ProjectSseHub();
		UUID projectId = UUID.randomUUID();
		SseEmitter emitter = hub.subscribe(projectId);
		assertThat(hub.subscriberCount(projectId)).isEqualTo(1);
		hub.onProjectEvent(ProjectRealtimeEvent.of(ProjectRealtimeEventType.TASKS_CHANGED, projectId, "task-1"));
		hub.remove(projectId, emitter);
		assertThat(hub.subscriberCount(projectId)).isEqualTo(0);
	}
}
