package com.saga.be.service.team;

import java.time.Duration;
import java.util.Optional;

public interface TeamPreviewStore {

	void save(String token, TeamPreviewSnapshot snapshot, Duration ttl);

	Optional<TeamPreviewSnapshot> find(String token);

	void delete(String token);
}
