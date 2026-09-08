package com.saga.be.service.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.IdentityMappingAction;
import com.saga.be.entity.enums.IdentityMappingStatus;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.integration.IdentityMap;
import com.saga.be.entity.integration.IdentityMappingHistory;
import com.saga.be.exception.IntegrationException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityLinkingServiceTest {

	private MemoryStore store;
	private IdentityLinkingService service;
	private UserAccount alice;
	private UserAccount bob;

	@BeforeEach
	void setUp() {
		store = new MemoryStore();
		service = new IdentityLinkingService(store);
		alice = user("alice");
		bob = user("bob");
		store.users.put(alice.getId(), alice);
		store.users.put(bob.getId(), bob);
	}

	@Test
	void firstGithubIdentityLinksSuccessfully() {
		IdentityLinkingService.LinkResult result = service.link(cmd(alice, IntegrationProvider.GITHUB, "111", "alice-gh"));
		assertFalse(result.claimedConflict());
		assertEquals(IdentityMappingAction.LINKED, result.action());
		assertTrue(result.identity().isPrimary());
		assertEquals(IdentityMappingStatus.ACTIVE, result.identity().getMappingStatus());
		assertEquals(1, store.history.size());
	}

	@Test
	void secondGithubIdentityLinksToSameUser() {
		service.link(cmd(alice, IntegrationProvider.GITHUB, "111", "alice-gh"));
		IdentityLinkingService.LinkResult second = service.link(cmd(alice, IntegrationProvider.GITHUB, "222", "alice-work"));
		assertFalse(second.claimedConflict());
		assertEquals(2, store.byUser(alice.getId(), IntegrationProvider.GITHUB).size());
		assertEquals(1, store.byUser(alice.getId(), IntegrationProvider.GITHUB).stream().filter(IdentityMap::isPrimary).count());
	}

	@Test
	void userMayHaveMultipleGithubAndJiraIdentities() {
		service.link(cmd(alice, IntegrationProvider.GITHUB, "111", "a"));
		service.link(cmd(alice, IntegrationProvider.GITHUB, "222", "b"));
		service.link(cmd(alice, IntegrationProvider.JIRA, "abc", "A"));
		service.link(cmd(alice, IntegrationProvider.JIRA, "xyz", "B"));
		assertEquals(2, store.byUser(alice.getId(), IntegrationProvider.GITHUB).size());
		assertEquals(2, store.byUser(alice.getId(), IntegrationProvider.JIRA).size());
	}

	@Test
	void sameGithubIdCannotLinkToAnotherUser() {
		service.link(cmd(alice, IntegrationProvider.GITHUB, "111", "alice-gh"));
		IdentityLinkingService.LinkResult conflict = service.link(cmd(bob, IntegrationProvider.GITHUB, "111", "bob-gh"));
		assertTrue(conflict.claimedConflict());
		assertEquals(alice.getId(), conflict.identity().getUserAccount().getId());
	}

	@Test
	void sameJiraAccountIdCannotLinkToAnotherUser() {
		service.link(cmd(alice, IntegrationProvider.JIRA, "acc-1", "Alice"));
		assertTrue(service.link(cmd(bob, IntegrationProvider.JIRA, "acc-1", "Bob")).claimedConflict());
	}

	@Test
	void usernameRenameDoesNotCreateNewIdentity() {
		service.link(cmd(alice, IntegrationProvider.GITHUB, "111", "old-login"));
		IdentityLinkingService.LinkResult again = service.link(cmd(alice, IntegrationProvider.GITHUB, "111", "new-login"));
		assertEquals(1, store.byUser(alice.getId(), IntegrationProvider.GITHUB).size());
		assertEquals("new-login", again.identity().getExternalUsername());
	}

	@Test
	void firstLinkSetsLinkedAtAndLastVerifiedAtTogether() {
		LocalDateTime first = LocalDateTime.of(2026, 1, 10, 8, 0);
		IdentityMap identity =
				service.link(cmdAt(alice, IntegrationProvider.JIRA, "jira-1", "Alice", first)).identity();
		assertEquals(first, identity.getLinkedAt());
		assertEquals(first, identity.getLastVerifiedAt());
		assertEquals(first, identity.getVerifiedAt());
	}

	@Test
	void reconnectPreservesLinkedAtAndAdvancesLastVerifiedAt() {
		LocalDateTime first = LocalDateTime.of(2026, 1, 10, 8, 0);
		LocalDateTime reconnect = LocalDateTime.of(2026, 3, 15, 14, 30);
		service.link(cmdAt(alice, IntegrationProvider.JIRA, "jira-1", "Alice", first));
		IdentityLinkingService.LinkResult again =
				service.link(cmdAt(alice, IntegrationProvider.JIRA, "jira-1", "Alice", reconnect));
		assertEquals(IdentityMappingAction.VERIFIED, again.action());
		assertEquals(first, again.identity().getLinkedAt());
		assertEquals(reconnect, again.identity().getLastVerifiedAt());
		assertEquals(reconnect, again.identity().getVerifiedAt());
	}

	@Test
	void failedConflictLinkDoesNotAdvanceLastVerifiedAtOnExistingOwner() {
		LocalDateTime first = LocalDateTime.of(2026, 1, 10, 8, 0);
		LocalDateTime attempt = LocalDateTime.of(2026, 4, 1, 9, 0);
		IdentityMap owner =
				service.link(cmdAt(alice, IntegrationProvider.JIRA, "jira-1", "Alice", first)).identity();
		IdentityLinkingService.LinkResult conflict =
				service.link(cmdAt(bob, IntegrationProvider.JIRA, "jira-1", "Bob", attempt));
		assertTrue(conflict.claimedConflict());
		assertEquals(IdentityMappingAction.LINK_FAILED, conflict.action());
		IdentityMap persisted = store.maps.get(owner.getId());
		assertEquals(first, persisted.getLinkedAt());
		assertEquals(first, persisted.getLastVerifiedAt());
		assertEquals(first, persisted.getVerifiedAt());
	}

	@Test
	void primaryTransitionWorks() {
		IdentityMap first = service.link(cmd(alice, IntegrationProvider.GITHUB, "111", "a")).identity();
		IdentityMap second = service.link(cmd(alice, IntegrationProvider.GITHUB, "222", "b")).identity();
		service.setPrimary(alice.getId(), second.getId(), alice, LocalDateTime.now());
		assertFalse(store.maps.get(first.getId()).isPrimary());
		assertTrue(store.maps.get(second.getId()).isPrimary());
	}

	@Test
	void unlinkPrimarySelectsAnotherPrimaryDeterministically() {
		IdentityMap first = service.link(cmd(alice, IntegrationProvider.GITHUB, "111", "a")).identity();
		IdentityMap second = service.link(cmd(alice, IntegrationProvider.GITHUB, "222", "b")).identity();
		service.unlink(alice.getId(), first.getId(), alice, LocalDateTime.now());
		assertEquals(IdentityMappingStatus.REVOKED, store.maps.get(first.getId()).getMappingStatus());
		assertTrue(store.maps.get(second.getId()).isPrimary());
	}

	@Test
	void mappingHistoryIsCreated() {
		service.link(cmd(alice, IntegrationProvider.GITHUB, "111", "a"));
		assertTrue(store.history.stream().anyMatch(row -> row.getAction() == IdentityMappingAction.LINKED));
	}

	@Test
	void unlinkOfForeignIdentityIsHidden() {
		IdentityMap aliceId = service.link(cmd(alice, IntegrationProvider.GITHUB, "111", "a")).identity();
		assertThrows(IntegrationException.class, () -> service.unlink(bob.getId(), aliceId.getId(), bob, LocalDateTime.now()));
	}

	private IdentityLinkingService.LinkCommand cmd(
			UserAccount user, IntegrationProvider provider, String subject, String login) {
		return cmdAt(user, provider, subject, login, LocalDateTime.now());
	}

	private IdentityLinkingService.LinkCommand cmdAt(
			UserAccount user,
			IntegrationProvider provider,
			String subject,
			String login,
			LocalDateTime now) {
		return new IdentityLinkingService.LinkCommand(
				user, user, provider, subject, login, login, null, null, now);
	}

	private static UserAccount user(String name) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail(name + "@example.com");
		account.setFullName(name);
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}

	static final class MemoryStore implements IdentityLinkingService.Store {
		private final Map<UUID, UserAccount> users = new ConcurrentHashMap<>();
		private final Map<UUID, IdentityMap> maps = new ConcurrentHashMap<>();
		private final List<IdentityMappingHistory> history = new ArrayList<>();

		@Override
		public Optional<UserAccount> lockUser(UUID userId) {
			return Optional.ofNullable(users.get(userId));
		}

		@Override
		public Optional<IdentityMap> findActiveByProviderSubject(IntegrationProvider provider, String subject) {
			return maps.values().stream()
					.filter(map -> map.getProvider() == provider
							&& subject.equals(map.getExternalAccountId())
							&& map.getMappingStatus().isActiveLink())
					.findFirst();
		}

		@Override
		public List<IdentityMap> findByUserAndProvider(UUID userId, IntegrationProvider provider) {
			return byUser(userId, provider);
		}

		List<IdentityMap> byUser(UUID userId, IntegrationProvider provider) {
			return maps.values().stream()
					.filter(map -> map.getUserAccount().getId().equals(userId) && map.getProvider() == provider)
					.toList();
		}

		@Override
		public Optional<IdentityMap> findById(UUID identityId) {
			return Optional.ofNullable(maps.get(identityId));
		}

		@Override
		public IdentityMap save(IdentityMap map) {
			if (map.getId() == null) {
				map.setId(UUID.randomUUID());
			}
			maps.put(map.getId(), map);
			return map;
		}

		@Override
		public IdentityMappingHistory saveHistory(IdentityMappingHistory row) {
			if (row.getId() == null) {
				row.setId(UUID.randomUUID());
			}
			history.add(row);
			return row;
		}
	}
}
