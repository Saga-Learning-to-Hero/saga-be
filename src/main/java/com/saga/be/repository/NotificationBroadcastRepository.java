package com.saga.be.repository;

import com.saga.be.entity.notification.NotificationBroadcast;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationBroadcastRepository extends JpaRepository<NotificationBroadcast, UUID> {

	Optional<NotificationBroadcast> findBySenderUser_IdAndIdempotencyKey(UUID senderUserId, String idempotencyKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(
			"""
			select b from NotificationBroadcast b
			where b.senderUser.id = :senderId and b.idempotencyKey = :key
			""")
	Optional<NotificationBroadcast> findBySenderUser_IdAndIdempotencyKeyForUpdate(
			@Param("senderId") UUID senderId, @Param("key") String idempotencyKey);
}
