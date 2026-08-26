package com.saga.be.integration.oauth;

import java.util.Optional;

public interface OAuthStateStore {

	void save(OAuthState state, java.time.Duration ttl);

	Optional<OAuthState> consume(String state);
}
