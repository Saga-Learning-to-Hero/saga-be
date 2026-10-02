package com.saga.be.repository;

import com.saga.be.entity.assistant.AssistantConversation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, UUID> {

	/** The conversation, only if it belongs to this user and this project. */
	@Query(
			"""
			select c from AssistantConversation c
			where c.id = :id and c.project.id = :projectId and c.userAccount.id = :userId
			""")
	Optional<AssistantConversation> findOwned(
			@Param("id") UUID id, @Param("projectId") UUID projectId, @Param("userId") UUID userId);

	/** This user's conversations about this project, most recently used first. */
	@Query(
			"""
			select c from AssistantConversation c
			where c.project.id = :projectId and c.userAccount.id = :userId
			order by coalesce(c.lastMessageAt, c.createdAt) desc, c.id desc
			""")
	List<AssistantConversation> findOwnedByProject(@Param("projectId") UUID projectId, @Param("userId") UUID userId);
}
