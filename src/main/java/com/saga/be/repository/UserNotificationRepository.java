package com.saga.be.repository;

import com.saga.be.entity.notification.UserNotification;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNotificationRepository extends JpaRepository<UserNotification, UUID> {

	@Query(
			value =
					"""
					select new com.saga.be.repository.NotificationInboxRow(
							n.id, n.notificationType, n.title, n.message, n.actionUrl, n.readAt, n.createdAt)
					from UserNotification n
					where n.recipientUser.id = :recipientId
					  and (:unreadOnly = false or n.readAt is null)
					order by n.createdAt desc, n.id desc
					""",
			countQuery =
					"""
					select count(n.id)
					from UserNotification n
					where n.recipientUser.id = :recipientId
					  and (:unreadOnly = false or n.readAt is null)
					""")
	Page<NotificationInboxRow> findInbox(
			@Param("recipientId") UUID recipientId, @Param("unreadOnly") boolean unreadOnly, Pageable pageable);

	long countByRecipientUser_IdAndReadAtIsNull(UUID recipientId);

	long countByRecipientUser_Id(UUID recipientId);

	void deleteByRecipientUser_Id(UUID recipientId);

	Optional<UserNotification> findByIdAndRecipientUser_Id(UUID id, UUID recipientUserId);

	Optional<UserNotification> findByRecipientUser_IdAndEventKey(UUID recipientUserId, String eventKey);

	long countByBroadcast_Id(UUID broadcastId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
			"""
			update UserNotification n
			set n.readAt = :readAt, n.updatedAt = :readAt
			where n.recipientUser.id = :recipientId
			  and n.readAt is null
			""")
	int markAllUnreadAsRead(@Param("recipientId") UUID recipientId, @Param("readAt") LocalDateTime readAt);
}
