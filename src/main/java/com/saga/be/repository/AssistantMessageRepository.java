package com.saga.be.repository;

import com.saga.be.entity.assistant.AssistantMessage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, UUID> {

	@Query(
			"""
			select m from AssistantMessage m
			where m.conversation.id = :conversationId
			order by m.createdAt asc, m.id asc
			""")
	List<AssistantMessage> findByConversation(@Param("conversationId") UUID conversationId);

	/** Questions this user asked since {@code since}, across every project: the daily quota. */
	@Query(
			"""
			select count(m) from AssistantMessage m
			where m.conversation.userAccount.id = :userId
			  and m.role = com.saga.be.entity.assistant.AssistantMessage.Role.USER
			  and m.createdAt >= :since
			""")
	long countQuestionsSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

	/** An assistant answer, only if its conversation belongs to this user and this project. */
	@Query(
			"""
			select m from AssistantMessage m
			where m.id = :id
			  and m.role = com.saga.be.entity.assistant.AssistantMessage.Role.ASSISTANT
			  and m.conversation.project.id = :projectId
			  and m.conversation.userAccount.id = :userId
			""")
	Optional<AssistantMessage> findOwnedAnswer(
			@Param("id") UUID id, @Param("projectId") UUID projectId, @Param("userId") UUID userId);
}
