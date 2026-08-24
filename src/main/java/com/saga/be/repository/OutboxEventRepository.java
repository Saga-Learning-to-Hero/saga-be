package com.saga.be.repository;

import com.saga.be.entity.enums.OutboxStatus;
import com.saga.be.entity.infra.OutboxEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

	List<OutboxEvent> findTop100ByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(
			OutboxStatus status, LocalDateTime availableAt);
}
