package com.saga.be.repository;

import com.saga.be.entity.notification.NotificationDelivery;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

	long countByNotification_Id(UUID notificationId);

	boolean existsByNotification_IdAndInstallation_Id(UUID notificationId, UUID installationId);

	@Query(
			"""
			select d from NotificationDelivery d
			join fetch d.notification n
			join fetch n.recipientUser
			join fetch d.installation i
			join fetch i.ownerUser
			where d.id = :id
			""")
	Optional<NotificationDelivery> findByIdWithNotificationAndInstallation(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from NotificationDelivery d where d.id = :id")
	Optional<NotificationDelivery> findByIdForUpdate(@Param("id") UUID id);

	@Query(
			"""
			SELECT d.id FROM NotificationDelivery d
			WHERE d.deliveryStatus = com.saga.be.entity.enums.DeliveryStatus.PENDING
			AND COALESCE(d.attemptCount, 0) < :maxAttempts
			AND (d.processingStartedAt IS NULL OR d.processingStartedAt <= :staleBefore)
			AND (d.lastAttemptAt IS NULL OR d.lastAttemptAt <= :retryBefore)
			ORDER BY d.createdAt ASC
			""")
	List<UUID> findClaimableIds(
			@Param("maxAttempts") int maxAttempts,
			@Param("staleBefore") LocalDateTime staleBefore,
			@Param("retryBefore") LocalDateTime retryBefore,
			Pageable pageable);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
			"""
			UPDATE NotificationDelivery d
			SET d.processingStartedAt = :now,
				d.lastAttemptAt = :now,
				d.attemptCount = COALESCE(d.attemptCount, 0) + 1
			WHERE d.id = :id
			AND d.deliveryStatus = com.saga.be.entity.enums.DeliveryStatus.PENDING
			AND COALESCE(d.attemptCount, 0) < :maxAttempts
			AND (d.processingStartedAt IS NULL OR d.processingStartedAt <= :staleBefore)
			AND (d.lastAttemptAt IS NULL OR d.lastAttemptAt <= :retryBefore)
			""")
	int claim(
			@Param("id") UUID id,
			@Param("now") LocalDateTime now,
			@Param("maxAttempts") int maxAttempts,
			@Param("staleBefore") LocalDateTime staleBefore,
			@Param("retryBefore") LocalDateTime retryBefore);
}
