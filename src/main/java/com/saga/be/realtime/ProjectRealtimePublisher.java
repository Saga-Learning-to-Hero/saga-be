package com.saga.be.realtime;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes project-scoped realtime invalidation events only after successful DB commit.
 * When no transaction is active (provider-only paths that already finished DB work), publishes immediately.
 */
@Component
public class ProjectRealtimePublisher {

	private final ApplicationEventPublisher events;

	public ProjectRealtimePublisher(ApplicationEventPublisher events) {
		this.events = events;
	}

	public void publish(ProjectRealtimeEventType type, UUID projectId) {
		publish(ProjectRealtimeEvent.of(type, projectId));
	}

	public void publish(ProjectRealtimeEventType type, UUID projectId, String entityId) {
		publish(ProjectRealtimeEvent.of(type, projectId, entityId));
	}

	public void publish(ProjectRealtimeEvent event) {
		if (event == null || event.projectId() == null || event.type() == null) {
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			events.publishEvent(event);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				events.publishEvent(event);
			}
		});
	}
}
