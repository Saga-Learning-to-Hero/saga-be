package com.saga.be.service.identity;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.IdentityMappingAction;
import com.saga.be.entity.enums.IdentityMappingStatus;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.integration.IdentityMap;
import com.saga.be.entity.integration.IdentityMappingHistory;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Global provider identity linking. Identities belong to a SAGA user, not a team.
 */
public class IdentityLinkingService {

	public record LinkCommand(
			UserAccount user,
			UserAccount actor,
			IntegrationProvider provider,
			String providerSubject,
			String login,
			String displayName,
			String avatarUrl,
			String instanceId,
			LocalDateTime now) {}

	public record LinkResult(IdentityMap identity, IdentityMappingAction action, boolean claimedConflict) {}

	public interface Store {
		Optional<UserAccount> lockUser(UUID userId);

		Optional<IdentityMap> findActiveByProviderSubject(IntegrationProvider provider, String subject);

		List<IdentityMap> findByUserAndProvider(UUID userId, IntegrationProvider provider);

		Optional<IdentityMap> findById(UUID identityId);

		IdentityMap save(IdentityMap map);

		IdentityMappingHistory saveHistory(IdentityMappingHistory history);
	}

	private final Store store;

	public IdentityLinkingService(Store store) {
		this.store = store;
	}

	public LinkResult link(LinkCommand command) {
		store.lockUser(command.user().getId())
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.EXTERNAL_IDENTITY_NOT_FOUND, HttpStatus.NOT_FOUND, "User was not found."));
		Optional<IdentityMap> existingActive =
				store.findActiveByProviderSubject(command.provider(), command.providerSubject());
		if (existingActive.isPresent()
				&& !existingActive.get().getUserAccount().getId().equals(command.user().getId())) {
			return new LinkResult(existingActive.get(), IdentityMappingAction.LINK_FAILED, true);
		}
		List<IdentityMap> own = store.findByUserAndProvider(command.user().getId(), command.provider());
		IdentityMap target = existingActive.orElseGet(() -> own.stream()
				.filter(map -> command.providerSubject().equals(map.getExternalAccountId()))
				.findFirst()
				.orElse(null));
		IdentityMappingAction action;
		IdentityMappingStatus previous = target == null ? null : target.getMappingStatus();
		if (target == null) {
			target = new IdentityMap();
			target.setUserAccount(command.user());
			target.setProvider(command.provider());
			target.setExternalAccountId(command.providerSubject());
			action = IdentityMappingAction.LINKED;
		} else if (!target.getMappingStatus().isActiveLink()) {
			action = IdentityMappingAction.RELINKED;
		} else {
			action = IdentityMappingAction.VERIFIED;
		}
		boolean firstPrimary = own.stream().noneMatch(map -> map.isPrimary() && map.getMappingStatus().isActiveLink())
				&& (target.getId() == null || !target.isPrimary());
		if (target.getId() == null || !target.getMappingStatus().isActiveLink()) {
			long activeCount = own.stream().filter(map -> map.getMappingStatus().isActiveLink()).count();
			if (target.getId() != null && !target.getMappingStatus().isActiveLink()) {
				firstPrimary = activeCount == 0;
			} else if (target.getId() == null) {
				firstPrimary = activeCount == 0;
			}
		}
		target.setExternalUsername(command.login());
		target.setProviderDisplayName(command.displayName());
		target.setProviderAvatarUrl(command.avatarUrl());
		target.setProviderInstanceId(command.instanceId());
		target.setMappingStatus(IdentityMappingStatus.ACTIVE);
		target.setVerifiedAt(command.now());
		target.setLastVerifiedAt(command.now());
		target.setLinkedAt(target.getLinkedAt() == null ? command.now() : target.getLinkedAt());
		target.setDisconnectedAt(null);
		target.setRevokedAt(null);
		if (firstPrimary) {
			target.setPrimary(true);
		}
		IdentityMap saved = store.save(target);
		store.saveHistory(history(saved, command, action, previous, IdentityMappingStatus.ACTIVE, command.now()));
		return new LinkResult(saved, action, false);
	}

	public IdentityMap setPrimary(UUID userId, UUID identityId, UserAccount actor, LocalDateTime now) {
		store.lockUser(userId);
		IdentityMap target = requireOwn(userId, identityId);
		if (!target.getMappingStatus().isActiveLink()) {
			throw new IntegrationException(
					IntegrationErrorCode.EXTERNAL_IDENTITY_NOT_FOUND,
					HttpStatus.NOT_FOUND,
					"Identity is not active.");
		}
		List<IdentityMap> own = store.findByUserAndProvider(userId, target.getProvider());
		for (IdentityMap map : own) {
			boolean shouldBePrimary = map.getId().equals(identityId);
			if (map.isPrimary() != shouldBePrimary) {
				map.setPrimary(shouldBePrimary);
				store.save(map);
				store.saveHistory(history(
						map,
						actor,
						IdentityMappingAction.PRIMARY_CHANGED,
						map.getMappingStatus(),
						map.getMappingStatus(),
						now));
			}
		}
		target.setPrimary(true);
		return store.save(target);
	}

	public IdentityMap unlink(UUID userId, UUID identityId, UserAccount actor, LocalDateTime now) {
		store.lockUser(userId);
		IdentityMap target = requireOwn(userId, identityId);
		boolean wasPrimary = target.isPrimary();
		IntegrationProvider provider = target.getProvider();
		target.setMappingStatus(IdentityMappingStatus.REVOKED);
		target.setPrimary(false);
		target.setDisconnectedAt(now);
		target.setRevokedAt(now);
		IdentityMap saved = store.save(target);
		store.saveHistory(
				history(saved, actor, IdentityMappingAction.UNLINKED, IdentityMappingStatus.ACTIVE, IdentityMappingStatus.REVOKED, now));
		if (wasPrimary) {
			store.findByUserAndProvider(userId, provider).stream()
					.filter(map -> map.getMappingStatus().isActiveLink())
					.min(Comparator.comparing(IdentityMap::getLinkedAt, Comparator.nullsLast(Comparator.naturalOrder()))
							.thenComparing(map -> map.getId().toString()))
					.ifPresent(next -> {
						next.setPrimary(true);
						store.save(next);
						store.saveHistory(history(
								next,
								actor,
								IdentityMappingAction.PRIMARY_CHANGED,
								next.getMappingStatus(),
								next.getMappingStatus(),
								now));
					});
		}
		return saved;
	}

	private IdentityMap requireOwn(UUID userId, UUID identityId) {
		IdentityMap map = store.findById(identityId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.EXTERNAL_IDENTITY_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Identity was not found."));
		if (!map.getUserAccount().getId().equals(userId)) {
			throw new IntegrationException(
					IntegrationErrorCode.EXTERNAL_IDENTITY_NOT_FOUND,
					HttpStatus.NOT_FOUND,
					"Identity was not found.");
		}
		return map;
	}

	private static IdentityMappingHistory history(
			IdentityMap map,
			LinkCommand command,
			IdentityMappingAction action,
			IdentityMappingStatus previous,
			IdentityMappingStatus next,
			LocalDateTime now) {
		return history(map, command.actor(), action, previous, next, now);
	}

	private static IdentityMappingHistory history(
			IdentityMap map,
			UserAccount actor,
			IdentityMappingAction action,
			IdentityMappingStatus previous,
			IdentityMappingStatus next,
			LocalDateTime now) {
		IdentityMappingHistory history = new IdentityMappingHistory();
		history.setIdentityMap(map);
		history.setUserAccount(map.getUserAccount());
		history.setProvider(map.getProvider());
		history.setExternalAccountId(map.getExternalAccountId());
		history.setAction(action);
		history.setPreviousStatus(previous == null ? null : previous.name());
		history.setNewStatus(next == null ? null : next.name());
		history.setPrimarySnapshot(map.isPrimary());
		history.setSource("OAUTH");
		history.setActorUser(actor);
		history.setOccurredAt(now);
		return history;
	}
}
