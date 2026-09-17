package com.saga.be.repository;

import com.saga.be.entity.github.Comment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

	/**
	 * Heatmap: {@code Object[]{UUID studentId, LocalDateTime createdAt}} for comments authored by a
	 * mapped student on a task, pull request, or git issue belonging to the project.
	 */
	@Query(
			"""
			select c.authorStudent.id, c.createdAt
			from Comment c
			left join c.task t
			left join c.pullRequest pr
			left join pr.repo prRepo
			left join c.gitIssue gi
			left join gi.repo giRepo
			where c.authorStudent is not null
			  and (
			    t.project.id = :projectId
			    or prRepo.project.id = :projectId
			    or giRepo.project.id = :projectId
			  )
			""")
	List<Object[]> findAuthorAndCreatedAtByProject(@Param("projectId") UUID projectId);
}
