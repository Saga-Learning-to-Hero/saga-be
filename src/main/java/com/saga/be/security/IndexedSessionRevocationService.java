package com.saga.be.security;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/**
 * Deletes every indexed Spring Session for a user. Principal index is {@code UserAccount.id}.
 * No Redis SCAN or wildcard deletes.
 */
@Service
public class IndexedSessionRevocationService {

	private static final Logger log = LoggerFactory.getLogger(IndexedSessionRevocationService.class);

	private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions;

	public IndexedSessionRevocationService(
			ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions) {
		this.sessions = sessions;
	}

	public int revokeAllForUser(UUID userId) {
		if (userId == null) {
			return 0;
		}
		FindByIndexNameSessionRepository<? extends Session> repository = sessions.getIfAvailable();
		if (repository == null) {
			log.warn("auth method=SESSION result=skipped category=ACCOUNT_DISABLED reason=repository-unavailable");
			return 0;
		}
		String principal = userId.toString();
		Map<String, ? extends Session> found = repository.findByPrincipalName(principal);
		int count = 0;
		for (String sessionId : found.keySet()) {
			repository.deleteById(sessionId);
			count++;
		}
		log.info("auth method=SESSION result=revoked category=ACCOUNT_DISABLED count={}", count);
		return count;
	}
}
