package com.saga.be.entity;

import java.util.EnumSet;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;

/**
 * UUID identifier that INSERTs when the application assigned the id before persist.
 * Default {@code GenerationType.UUID} treats a non-null id as detached and merge then fails.
 */
public final class AssignedUuidGenerator implements BeforeExecutionGenerator {

	@Override
	public Object generate(
			SharedSessionContractImplementor session, Object owner, Object currentValue, EventType eventType) {
		if (currentValue instanceof UUID assigned) {
			return assigned;
		}
		if (owner instanceof BaseEntity entity && entity.getId() != null) {
			return entity.getId();
		}
		return UUID.randomUUID();
	}

	@Override
	public EnumSet<EventType> getEventTypes() {
		return EventTypeSets.INSERT_ONLY;
	}

	@Override
	public boolean allowAssignedIdentifiers() {
		return true;
	}
}
