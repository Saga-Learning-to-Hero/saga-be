package com.saga.be.service.projection;

import com.saga.be.entity.github.GitCommit;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface CommitMessageCandidateQuery {

	/** Project-scoped commits whose message/headRef match any of the Jira keys. Bounded result. */
	List<GitCommit> findByProjectAndKeys(UUID projectId, Collection<String> keys);
}
