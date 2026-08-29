package com.saga.be.repository;

import com.saga.be.entity.notification.EmailOutbox;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, UUID> {

	@Query(
			"""
			SELECT e.id FROM EmailOutbox e
			WHERE (
				e.deliveryStatus = com.saga.be.entity.enums.EmailDeliveryStatus.PENDING
				AND (e.scheduledAt IS NULL OR e.scheduledAt <= :now)
			)
			OR (
				e.deliveryStatus = com.saga.be.entity.enums.EmailDeliveryStatus.PROCESSING
				AND e.lastAttemptAt IS NOT NULL
				AND e.lastAttemptAt <= :staleBefore
			)
			ORDER BY e.createdAt ASC
			""")
	List<UUID> findClaimableIds(
			@Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore, Pageable pageable);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
			"""
			UPDATE EmailOutbox e
			SET e.deliveryStatus = com.saga.be.entity.enums.EmailDeliveryStatus.PROCESSING,
				e.lastAttemptAt = :now,
				e.attemptCount = COALESCE(e.attemptCount, 0) + 1
			WHERE e.id = :id
			AND (
				(
					e.deliveryStatus = com.saga.be.entity.enums.EmailDeliveryStatus.PENDING
					AND (e.scheduledAt IS NULL OR e.scheduledAt <= :now)
				)
				OR (
					e.deliveryStatus = com.saga.be.entity.enums.EmailDeliveryStatus.PROCESSING
					AND e.lastAttemptAt IS NOT NULL
					AND e.lastAttemptAt <= :staleBefore
				)
			)
			""")
	int claim(
			@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore);
}
