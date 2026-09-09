package com.saga.be.integration.oauth;

import com.saga.be.dto.integration.GithubReconnectCandidateResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Short-lived display candidates after OAuth when multiple eligible installations remain. */
public interface GithubReconnectCandidateStore {

	void save(UUID userId, UUID projectId, List<GithubReconnectCandidateResponse> candidates, Duration ttl);

	List<GithubReconnectCandidateResponse> find(UUID userId, UUID projectId);

	void clear(UUID userId, UUID projectId);
}
