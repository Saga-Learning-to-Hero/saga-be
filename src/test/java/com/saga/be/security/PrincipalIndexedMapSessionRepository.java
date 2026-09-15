package com.saga.be.security;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.Session;

/**
 * In-memory indexed session store that mirrors Spring Session's principal-name index:
 * {@code Authentication.getName()} from {@code SPRING_SECURITY_CONTEXT}.
 */
public class PrincipalIndexedMapSessionRepository implements FindByIndexNameSessionRepository<MapSession> {

	private final Map<String, MapSession> sessions = new ConcurrentHashMap<>();
	private final Map<String, Set<String>> byPrincipal = new ConcurrentHashMap<>();

	@Override
	public MapSession createSession() {
		return new MapSession();
	}

	@Override
	public void save(MapSession session) {
		unindex(session.getId());
		sessions.put(session.getId(), session);
		String principal = principalName(session);
		if (principal != null && !principal.isBlank()) {
			byPrincipal.computeIfAbsent(principal, key -> ConcurrentHashMap.newKeySet()).add(session.getId());
		}
	}

	@Override
	public MapSession findById(String id) {
		return sessions.get(id);
	}

	@Override
	public void deleteById(String id) {
		unindex(id);
		sessions.remove(id);
	}

	@Override
	public Map<String, MapSession> findByIndexNameAndIndexValue(String indexName, String indexValue) {
		if (!PRINCIPAL_NAME_INDEX_NAME.equals(indexName) || indexValue == null) {
			return Map.of();
		}
		Set<String> ids = byPrincipal.getOrDefault(indexValue, Set.of());
		Map<String, MapSession> found = new ConcurrentHashMap<>();
		for (String id : ids) {
			MapSession session = sessions.get(id);
			if (session != null) {
				found.put(id, session);
			}
		}
		return found;
	}

	private void unindex(String sessionId) {
		byPrincipal.values().forEach(ids -> ids.remove(sessionId));
	}

	private static String principalName(Session session) {
		Object indexed = session.getAttribute(PRINCIPAL_NAME_INDEX_NAME);
		if (indexed instanceof String name && !name.isBlank()) {
			return name;
		}
		Object context = session.getAttribute("SPRING_SECURITY_CONTEXT");
		if (context instanceof SecurityContext securityContext) {
			Authentication authentication = securityContext.getAuthentication();
			if (authentication != null) {
				return authentication.getName();
			}
		}
		return null;
	}
}
