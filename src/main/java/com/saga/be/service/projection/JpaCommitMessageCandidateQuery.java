package com.saga.be.service.projection;

import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class JpaCommitMessageCandidateQuery implements CommitMessageCandidateQuery {

	static final int MAX_CANDIDATE_COMMITS = 500;

	private final EntityManager entityManager;

	public JpaCommitMessageCandidateQuery(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override
	public List<GitCommit> findByProjectAndKeys(UUID projectId, Collection<String> keys) {
		if (projectId == null || keys == null || keys.isEmpty()) {
			return List.of();
		}
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<GitCommit> query = cb.createQuery(GitCommit.class);
		Root<GitCommit> root = query.from(GitCommit.class);
		Join<GitCommit, GitRepo> repo = root.join("repo");
		root.fetch("repo");
		List<Predicate> keyPredicates = new ArrayList<>(keys.size());
		for (String key : keys) {
			String pattern = "%" + key + "%";
			Predicate messageMatch = cb.like(cb.upper(root.get("message")), pattern);
			Predicate headMatch = cb.like(cb.upper(cb.coalesce(root.get("headRef"), cb.literal(""))), pattern);
			keyPredicates.add(cb.or(messageMatch, headMatch));
		}
		query.select(root)
				.distinct(true)
				.where(cb.and(
						cb.equal(repo.get("project").get("id"), projectId),
						cb.or(keyPredicates.toArray(Predicate[]::new))));
		return entityManager.createQuery(query).setMaxResults(MAX_CANDIDATE_COMMITS).getResultList();
	}
}
