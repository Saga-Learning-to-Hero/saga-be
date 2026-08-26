package com.saga.be.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.enums.OutboxStatus;
import com.saga.be.entity.infra.OutboxEvent;
import com.saga.be.repository.OutboxEventRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class OutboxPublisher {

	private final OutboxEventRepository outbox;
	private final ObjectMapper mapper;

	public OutboxPublisher(OutboxEventRepository outbox, ObjectMapper mapper) {
		this.outbox = outbox;
		this.mapper = mapper;
	}

	@Transactional
	public void publish(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
		OutboxEvent event = new OutboxEvent();
		event.setAggregateType(aggregateType);
		event.setAggregateId(aggregateId);
		event.setEventType(eventType);
		try {
			event.setPayload(mapper.writeValueAsString(payload));
		} catch (Exception ex) {
			event.setPayload("{}");
		}
		event.setStatus(OutboxStatus.PENDING);
		event.setAttemptCount(0);
		event.setAvailableAt(LocalDateTime.now());
		outbox.save(event);
	}
}
