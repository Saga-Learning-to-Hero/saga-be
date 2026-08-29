package com.saga.be.service.roster;

import java.time.Duration;
import java.util.Optional;

public interface RosterPreviewStore {

	void save(String token, RosterPreviewSnapshot snapshot, Duration ttl);

	Optional<RosterPreviewSnapshot> find(String token);

	void delete(String token);
}
