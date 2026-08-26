package com.saga.be.integration.oauth;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface PendingJiraConnectStore {

	void save(PendingJiraConnect pending, Duration ttl);

	Optional<PendingJiraConnect> get(UUID userId, UUID projectId);

	Optional<PendingJiraConnect> consume(UUID userId, UUID projectId);
}
